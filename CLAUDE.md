# vaccine-backend

Kimball-style vaccination warehouse (refactor in progress): Postgres + Flyway, a Python ELT
(`elt/`), dbt marts (`dbt/`) and a Spring Boot 4.1 API on Java 21. Plan: `docs/REFACTOR_PLAN.md`,
decisions: `docs/DECISIONS.md`, contracts: `docs/data-model.md`, sources: `docs/data-sources.md`.

## Commands
- `make lint` (Spotless, ruff, ruff format, mypy, dbt parse: same as CI), `make format`
- `make test-fast` (no Docker), `make test` = `test-java` (`mvn verify`) + `test-python`
  (pytest incl. Testcontainers, `-m "not external"`)
- `make up` / `make down` (compose project `vaccine-backend`), `make load-reference`,
  `make dbt-build`, `make demo` (up + legacy seed + load-reference + dbt build + curl)
- ELT and dbt by hand: `uv run --project elt vaccine-elt ...`,
  `uv run --project elt dbt <cmd> --project-dir dbt --profiles-dir dbt` with libpq `PG*` env set
  (the Makefile maps them from `.env`)
- No JDK 21 on PATH? Point `JAVA_HOME` at one; `.tools/` is gitignored for a local portable JDK.

## Repo map
- `src/main/java/io/github/emanuelluis/vaccine/`: API (`api/dimension`, `api/fact`)
- `src/main/resources/db/migration/`: Flyway migrations (history in schema `ops`)
- `elt/src/vaccine_elt/`: `fetch.py` + `sources/` (public endpoints -> snapshots), `manifest.py`,
  `contracts.py` (typed raw contracts), `load.py` (load-reference), `runlog.py` (ops tables)
- `data/sources/`: vendored snapshots + `manifest.json` (sha256-verified on load)
- `dbt/`: staging, `intermediate/int_health_unit_versions` (SCD2), marts, seeds, singular tests
- `src/python/`: legacy CSV loaders for the legacy endpoints (removed with them in phase 4)
- `compose.yaml`, `Dockerfile`, `Makefile`, `.github/workflows/ci.yml`

## Invariants
- Flyway owns all DDL; never `ddl-auto=update`, never `DROP SCHEMA` in bootstrap scripts.
  Applied migrations are immutable: add a new version instead of editing one.
- No credentials in the repo: config from env, documented in `.env.example`.
- Data is public (IBGE, CNES) or synthetic; record source URL and license in the data manifest.
  Snapshots change only through `vaccine-elt fetch` (deterministic gzip, sha256 in the manifest).
- Loads are idempotent: TRUNCATE + COPY per raw table in one transaction; `rows_read =
  rows_loaded + rows_rejected` in `ops.load_run_table`. Never read the wall clock in data paths
  (run metadata timestamps only).
- dbt owns `staging`, `intermediate`, `marts`, `reference`; Flyway owns `raw`, `ops`, `public`.
  Mart columns follow `docs/data-model.md`; change both in the same PR.

## Conventions
- English for code, docs and commits (Conventional Commits). Integration tests end in `IT`.
- Format with Spotless (palantir-java-format) before committing.
- Python 3.12, ruff line length 100 (+ B, BLE, DTZ, I, UP), mypy strict on `elt/src`; pytest
  markers `db` (Docker) and `external` (network, never in CI).
- dbt 1.12: generic test arguments under `arguments:`; marts have enforced contracts.
