.DEFAULT_GOAL := help
SHELL := /usr/bin/env bash

MVN := ./mvnw -B -ntp
COMPOSE := docker compose
UV := uv run --project elt
DBT := $(UV) dbt
# dbt takes these options after the subcommand only: `$(DBT) <command> $(DBT_DIRS)`.
DBT_DIRS := --project-dir dbt --profiles-dir dbt
DBT_RESULTS := dbt/target/run_results.json
API_URL ?= http://localhost:8080
KEYCLOAK_URL ?= http://localhost:8180
ROWS ?= 200000
RATE ?= 2
SEED ?= 42

# Load .env and map it to the libpq variables read by the ELT and dbt, so the password is defined
# once (in .env).
WITH_PG = set -a && . ./.env && set +a && \
	export PGHOST=localhost PGPORT="$${POSTGRES_PORT:-5432}" PGDATABASE="$${POSTGRES_DB:-vaccine}" \
	PGUSER="$${POSTGRES_USER:-vaccine}" PGPASSWORD="$$POSTGRES_PASSWORD" &&
# dbt renders profiles.yml even for parse; these placeholders never reach a database.
NO_DB = PGUSER=lint PGPASSWORD=lint PGDATABASE=lint

.PHONY: help install lint format test-fast test test-java test-python env up down logs \
	load-reference generate dbt-deps dbt-build worker reconcile demo smoke speed-check openapi simulate \
	web-install web-lint web-test web-build e2e screenshots \
	d2-install diagrams diagrams-check diagrams-watch

help: ## Show this help
	@grep -E '^[a-zA-Z0-9_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-15s\033[0m %s\n", $$1, $$2}'

install: ## Resolve Maven deps, sync the ELT env (uv) and install dbt packages
	$(MVN) dependency:go-offline
	uv sync --locked --project elt
	$(NO_DB) $(DBT) deps $(DBT_DIRS)

lint: dbt-deps ## Everything the CI lint job runs: Spotless, ruff, mypy, dbt parse
	$(MVN) spotless:check
	$(UV) ruff check elt
	$(UV) ruff format --check elt
	cd elt && uv run mypy
	$(NO_DB) $(DBT) parse $(DBT_DIRS)

format: ## Apply Spotless and ruff formatting
	$(MVN) spotless:apply
	$(UV) ruff check --fix elt
	$(UV) ruff format elt

test-fast: ## Unit tests only, no Docker (Java surefire + pytest default selection)
	$(MVN) test
	cd elt && uv run pytest

test: test-java test-python ## Everything except external endpoints (needs Docker)

test-java: ## Java unit + Testcontainers integration tests (mvn verify)
	$(MVN) verify

test-python: ## ELT tests incl. Testcontainers (-m "not external")
	cd elt && uv run pytest -m "not external"

env: ## Create .env from .env.example when missing
	@test -f .env || { cp .env.example .env && echo "created .env from .env.example"; }

up: env ## Build and start Postgres, Keycloak, API and web, wait until healthy
	$(COMPOSE) up -d --build --wait

down: ## Stop the stack and remove its volumes
	$(COMPOSE) down -v

logs: ## Follow the stack logs
	$(COMPOSE) logs -f

load-reference: env ## Verify the vendored public snapshots and load them into raw
	$(WITH_PG) $(UV) vaccine-elt load-reference

generate: env ## Replace the synthetic events, lots and receipts (ROWS, SEED)
	$(WITH_PG) $(UV) vaccine-elt generate --rows $(ROWS) --seed $(SEED)

dbt-deps: ## Install dbt packages (dbt_utils)
	$(NO_DB) $(DBT) deps $(DBT_DIRS)

dbt-build: env dbt-deps ## Build and test staging, intermediate and marts, then record the results in ops
	$(WITH_PG) rm -f $(DBT_RESULTS) && { 		$(DBT) build $(DBT_DIRS); status=$$?; 		if [ -f $(DBT_RESULTS) ]; then 			$(UV) vaccine-elt record-dbt-results --run-results $(DBT_RESULTS) 				|| echo "warning: dbt results were not recorded" >&2; 		fi; 		exit $$status; }

worker: env ## Run the data-management worker from the host (needs the stack's Postgres; Ctrl-C stops)
	$(WITH_PG) $(UV) vaccine-elt worker

reconcile: env ## Check live.dose_daily and the serving merge against the landing tables (exit 1 on a diff)
	$(WITH_PG) $(UV) vaccine-elt reconcile

demo: up load-reference generate dbt-build reconcile smoke ## One command: stack, data, dbt marts, reconciliation, API answering

smoke: ## Call every API endpoint with a Keycloak token and require rows (stack and marts must be up)
	KEYCLOAK_URL=$(KEYCLOAK_URL) bash scripts/smoke.sh $(API_URL)

speed-check: ## Post 3 doses, check today +3, run dbt-build + reconcile, check the total is unchanged
	KEYCLOAK_URL=$(KEYCLOAK_URL) bash scripts/speed-check.sh $(API_URL)

simulate: env ## Run the simulator from the host against the running stack (RATE, MAX_DOSES; Ctrl-C stops)
	set -a && . ./.env && set +a && API_URL=$(API_URL) \
	TOKEN_URL=$(KEYCLOAK_URL)/realms/vaccine/protocol/openid-connect/token \
	$(UV) vaccine-elt simulate --rate $(RATE) $(if $(MAX_DOSES),--max-doses $(MAX_DOSES))

openapi: ## Regenerate docs/api/openapi.json from the controllers (no Docker needed; commit the result)
	$(MVN) test-compile failsafe:integration-test failsafe:verify -Dit.test=OpenApiSpecIT -Dopenapi.write=true

web-install: ## Install the web app dependencies from the lockfile (npm ci)
	cd web && npm ci

web-lint: ## Everything the CI web job checks before tests: eslint and the TypeScript compiler
	cd web && npm run lint && npm run typecheck

web-test: ## Web unit tests (Vitest)
	cd web && npm test

web-build: ## Production build of the SPA into web/dist
	cd web && npm run build

e2e: env ## Browser e2e (Playwright) against the running stack; run make demo first
	set -a && . ./.env && set +a && cd web && npx playwright test

screenshots: env ## Regenerate docs/screenshots from the running stack
	set -a && . ./.env && set +a && cd web && npm run screenshots

# Diagrams: docs/diagrams/<name>.d2 rendered to <name>.svg (committed). One pinned D2 everywhere.
# Layout engine, themes and padding live in docs/diagrams/_style.d2 (d2-config), so d2 runs in a
# clean environment: D2_* and SCALE variables would override that config. --scale 1 is not a
# d2-config key; it writes the natural width and height, so GitHub never upscales a small diagram.
# These recipes start with `set -euo pipefail` because this Makefile sets no global .SHELLFLAGS
# (dbt-build needs to keep going after a failing dbt build to record its results).
D2_VERSION := 0.9.0
# sha256 of d2-v$(D2_VERSION)-linux-amd64.tar.gz, from the release's SHA256SUMS
D2_SHA256 := 5669ddc46b99e942cc96078f4a4e36d5e62103348f4c05179ede27802fdd87a9
D2_HOME ?= $(HOME)/.local/share/d2/v$(D2_VERSION)
D2 ?= $(D2_HOME)/bin/d2
D2_RUN = env -i PATH="$$PATH" HOME="$$HOME" "$(D2)" --scale 1
DIAGRAMS := $(filter-out docs/diagrams/_%,$(wildcard docs/diagrams/*.d2))

d2-install: ## Install the pinned D2 (Linux x86_64) into D2_HOME, checksum verified
	@set -euo pipefail; \
	if [ -x "$(D2_HOME)/bin/d2" ]; then echo "D2 v$(D2_VERSION) already in $(D2_HOME)"; exit 0; fi; \
	[ "$$(uname -sm)" = "Linux x86_64" ] || { echo "d2-install covers Linux x86_64 only;" \
		"install D2 v$(D2_VERSION) yourself and pass D2=/path/to/d2" >&2; exit 1; }; \
	tmp="$$(mktemp -d)"; trap 'rm -rf "$$tmp"' EXIT; \
	curl -fsSLo "$$tmp/d2.tar.gz" \
		"https://github.com/terrastruct/d2/releases/download/v$(D2_VERSION)/d2-v$(D2_VERSION)-linux-amd64.tar.gz"; \
	echo "$(D2_SHA256)  $$tmp/d2.tar.gz" | sha256sum --check --strict; \
	tar -xzf "$$tmp/d2.tar.gz" -C "$$tmp"; \
	mkdir -p "$(D2_HOME)/bin"; \
	install -m 0755 "$$tmp/d2-v$(D2_VERSION)/bin/d2" "$(D2_HOME)/bin/d2"; \
	"$(D2_HOME)/bin/d2" --version

diagrams: ## Render docs/diagrams/*.d2 to SVG with the pinned D2 (run make d2-install once)
	@set -euo pipefail; v="$$($(D2_RUN) --version 2>/dev/null || true)"; [ "$$v" = "v$(D2_VERSION)" ] || { \
		echo "need D2 v$(D2_VERSION) at $(D2), found '$${v:-nothing}': run make d2-install" >&2; exit 1; }
	@set -euo pipefail; for f in $(DIAGRAMS); do $(D2_RUN) "$$f" "$${f%.d2}.svg"; done

diagrams-check: diagrams ## Render, then fail if a committed SVG is stale, missing or has no source
	@set -euo pipefail; shopt -s nullglob; for s in docs/diagrams/*.svg; do \
		[ -f "$${s%.svg}.d2" ] || { echo "$$s has no .d2 source" >&2; exit 1; }; done
	@set -euo pipefail; changed="$$(git status --porcelain --untracked-files=all -- 'docs/diagrams/*.svg')"; \
	if [ -n "$$changed" ]; then echo "$$changed" >&2; \
		echo "rendered SVGs differ from the committed ones: run make diagrams and commit" >&2; exit 1; fi

diagrams-watch: ## Live preview in the browser while editing: make diagrams-watch NAME=architecture
	@# NAME only from the command line: WSL exports NAME (the hostname) into every shell.
	@[ "$(origin NAME)" = "command line" ] && [ -f "docs/diagrams/$(NAME).d2" ] || { \
		echo "usage: make diagrams-watch NAME=<one of: $(basename $(notdir $(DIAGRAMS)))>" >&2; exit 1; }
	$(D2_RUN) --watch --browser "$${BROWSER:-0}" docs/diagrams/$(NAME).d2 docs/diagrams/$(NAME).svg
