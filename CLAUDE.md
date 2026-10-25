# vaccine-backend

Kimball-style vaccination warehouse (refactor in progress): Postgres + Flyway, Spring Boot 4.1
API on Java 21; dbt marts and a Python ELT arrive in later phases. Plan: `docs/REFACTOR_PLAN.md`,
decisions: `docs/DECISIONS.md`.

## Commands
- `make lint` (Spotless check, same as CI), `make format`
- `make test-fast` (no Docker), `make test` (`mvn verify`, Testcontainers, needs Docker)
- `make up` / `make down` (compose project `vaccine-backend`), `make demo` (up + seed + curl)
- No JDK 21 on PATH? Point `JAVA_HOME` at one; `.tools/` is gitignored for a local portable JDK.

## Repo map
- `src/main/java/io/github/emanuelluis/vaccine/`: API (`api/dimension`, `api/fact`)
- `src/main/resources/db/migration/`: Flyway migrations (history in schema `ops`)
- `src/python/`: legacy CSV loaders (replaced by the ELT package in a later phase)
- `compose.yaml`, `Dockerfile`, `Makefile`, `.github/workflows/ci.yml`

## Invariants
- Flyway owns all DDL; never `ddl-auto=update`, never `DROP SCHEMA` in bootstrap scripts.
  Applied migrations are immutable: add a new version instead of editing one.
- No credentials in the repo: config from env, documented in `.env.example`.
- Data is public (IBGE, CNES) or synthetic; record source URL and license in the data manifest.

## Conventions
- English for code, docs and commits (Conventional Commits). Integration tests end in `IT`.
- Format with Spotless (palantir-java-format) before committing.
