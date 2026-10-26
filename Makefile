.DEFAULT_GOAL := help
SHELL := /usr/bin/env bash

MVN := ./mvnw -B -ntp
COMPOSE := docker compose
UV := uv run --project elt
DBT := $(UV) dbt
# dbt takes these options after the subcommand only: `$(DBT) <command> $(DBT_DIRS)`.
DBT_DIRS := --project-dir dbt --profiles-dir dbt
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
	load-reference generate dbt-deps dbt-build reconcile demo smoke speed-check openapi simulate \
	web-install web-lint web-test web-build

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-15s\033[0m %s\n", $$1, $$2}'

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

dbt-build: env dbt-deps ## Build and test staging, intermediate and marts
	$(WITH_PG) $(DBT) build $(DBT_DIRS)

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
