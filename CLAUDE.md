# vaccine-warehouse

Kimball-style vaccination warehouse: Postgres + Flyway, a Python ELT (`elt/`), dbt marts
(`dbt/`) and a read-only Spring Boot 4.1 API on Java 21. Plan: `docs/REFACTOR_PLAN.md`,
decisions: `docs/DECISIONS.md`, contracts: `docs/data-model.md`, sources: `docs/data-sources.md`.

## Commands
- `make lint` (Spotless, ruff, ruff format, mypy, dbt parse: same as CI), `make format`
- `make test-fast` (no Docker), `make test` = `test-java` (`mvn verify`) + `test-python`
  (pytest incl. Testcontainers, `-m "not external"`)
- `make up` / `make down` (compose project `vaccine-warehouse`), `make load-reference`,
  `make generate` (`ROWS`, `SEED`), `make dbt-build`, `make smoke`, `make demo` (up +
  load-reference + generate + dbt build + smoke)
- ELT and dbt by hand: `uv run --project elt vaccine-elt ...`,
  `uv run --project elt dbt <cmd> --project-dir dbt --profiles-dir dbt` with libpq `PG*` env set
  (the Makefile maps them from `.env`)
- No JDK 21 on PATH? Point `JAVA_HOME` at one; `.tools/` is gitignored for a local portable JDK.

## Repo map
- `src/main/java/io/github/emanuelluis/vaccine/`: `analytics/` (doses, coverage), `units/`
  (SCD2 history), `runs/` (ops runs), `web/` (envelope, problem details). `JdbcClient` + records,
  no JPA.
- `src/main/resources/db/migration/`: Flyway migrations (history in schema `ops`)
- `elt/src/vaccine_elt/`: `fetch.py` + `sources/` (public endpoints -> snapshots), `manifest.py`,
  `contracts.py` (typed raw contracts), `load.py` (load-reference), `runlog.py` (ops tables)
- `data/sources/`: vendored snapshots + `manifest.json` (sha256-verified on load)
- `dbt/`: staging, `intermediate/int_health_unit_versions` (SCD2), marts, seeds, singular tests
- `elt/src/vaccine_elt/generator/`: seeded synthetic events, lots, receipts (`generate`)
- `scripts/smoke.sh`: endpoint smoke test (`make smoke`, CI end-to-end job)
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
- dbt owns `staging`, `intermediate`, `marts`, `reference`; Flyway owns `raw`, `ops`.
  Mart columns follow `docs/data-model.md`; change the dbt contract, the API records/queries and
  `src/test/resources/fixtures/marts.sql` in the same PR.
- The API is read-only and only reads `marts` and `ops`; a missing mart answers 503, never 500.

## Conventions
- English for code, docs and commits (Conventional Commits). Integration tests end in `IT`.
- Format with Spotless (palantir-java-format) before committing.
- Python 3.12, ruff line length 100 (+ B, BLE, DTZ, I, UP), mypy strict on `elt/src`; pytest
  markers `db` (Docker) and `external` (network, never in CI).
- dbt 1.12: generic test arguments under `arguments:`; marts have enforced contracts.
