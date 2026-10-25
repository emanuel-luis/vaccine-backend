.DEFAULT_GOAL := help
SHELL := /usr/bin/env bash

MVN := ./mvnw -B -ntp
COMPOSE := docker compose
UV := uv run --project elt
DBT := $(UV) dbt
# dbt takes these options after the subcommand only: `$(DBT) <command> $(DBT_DIRS)`.
DBT_DIRS := --project-dir dbt --profiles-dir dbt
API_URL ?= http://localhost:8080

# Load .env and map it to the libpq variables read by the ELT, dbt and the legacy loader, so the
# password is defined once (in .env).
WITH_PG = set -a && . ./.env && set +a && \
	export PGHOST=localhost PGPORT="$${POSTGRES_PORT:-5432}" PGDATABASE="$${POSTGRES_DB:-vaccine}" \
	PGUSER="$${POSTGRES_USER:-vaccine}" PGPASSWORD="$$POSTGRES_PASSWORD" &&
# dbt renders profiles.yml even for parse; these placeholders never reach a database.
NO_DB = PGUSER=lint PGPASSWORD=lint PGDATABASE=lint

.PHONY: help install lint format test-fast test test-java test-python env up down logs seed \
	load-reference dbt-deps dbt-build demo

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

up: env ## Build and start Postgres + API (Flyway migrates on start), wait until healthy
	$(COMPOSE) up -d --build --wait

down: ## Stop the stack and remove its volumes
	$(COMPOSE) down -v

logs: ## Follow the stack logs
	$(COMPOSE) logs -f

seed: env ## Load the legacy dimension CSVs (legacy endpoints) into the running Postgres
	$(WITH_PG) uv run --no-project --python 3.12 --with pandas --with sqlalchemy \
		--with psycopg2-binary python src/python/mock_data.py

load-reference: env ## Verify the vendored public snapshots and load them into raw
	$(WITH_PG) $(UV) vaccine-elt load-reference

dbt-deps: ## Install dbt packages (dbt_utils)
	$(NO_DB) $(DBT) deps $(DBT_DIRS)

dbt-build: env dbt-deps ## Build and test staging, intermediate and marts
	$(WITH_PG) $(DBT) build $(DBT_DIRS)

demo: up seed load-reference dbt-build ## One command: stack, reference data, dbt marts, API answering
	curl -fsS "$(API_URL)/actuator/health" && echo
	curl -fsS "$(API_URL)/api/dimension?size=3" && echo
