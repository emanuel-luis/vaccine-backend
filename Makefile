.DEFAULT_GOAL := help
SHELL := /usr/bin/env bash

MVN := ./mvnw -B -ntp
COMPOSE := docker compose
API_URL ?= http://localhost:8080

.PHONY: help install lint format test-fast test env up down logs seed demo

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-10s\033[0m %s\n", $$1, $$2}'

install: ## Resolve Maven dependencies (needs JDK 21)
	$(MVN) dependency:go-offline

lint: ## Check formatting (same command as the CI lint job)
	$(MVN) spotless:check

format: ## Apply formatting
	$(MVN) spotless:apply

test-fast: ## Unit and web-slice tests, no Docker
	$(MVN) test

test: ## Full suite: unit + Testcontainers integration tests (needs Docker)
	$(MVN) verify

env: ## Create .env from .env.example when missing
	@test -f .env || { cp .env.example .env && echo "created .env from .env.example"; }

up: env ## Build and start Postgres + API, wait until healthy
	$(COMPOSE) up -d --build --wait

down: ## Stop the stack and remove its volumes
	$(COMPOSE) down -v

logs: ## Follow the stack logs
	$(COMPOSE) logs -f

seed: env ## Load the legacy dimension CSVs into the running Postgres
	set -a && . ./.env && set +a && \
	PGHOST=localhost PGPORT="$${POSTGRES_PORT:-5432}" PGDATABASE="$${POSTGRES_DB:-vaccine}" \
	PGUSER="$${POSTGRES_USER:-vaccine}" PGPASSWORD="$$POSTGRES_PASSWORD" \
	uv run --no-project --python 3.12 --with pandas --with sqlalchemy --with psycopg2-binary \
	  python src/python/mock_data.py

demo: up seed ## One command: stack up, dimensions loaded, API answering
	curl -fsS "$(API_URL)/actuator/health" && echo
	curl -fsS "$(API_URL)/api/dimension?size=3" && echo
