# vaccine-warehouse

Kimball-style vaccination warehouse with a Postgres speed layer: Postgres + Flyway, a Python ELT
(`elt/`), dbt marts (`dbt/`) and a Spring Boot 4.1 API on Java 21. Plans: `docs/REFACTOR_PLAN.md`
and `docs/WEB_PLATFORM_PLAN.md` (real-time dashboards, delivered in PRs), decisions:
`docs/DECISIONS.md`, contracts: `docs/data-model.md`, sources: `docs/data-sources.md`.

## Commands
- `make lint` (Spotless, ruff, ruff format, mypy, dbt parse: same as CI), `make format`
- `make test-fast` (no Docker), `make test` = `test-java` (`mvn verify`) + `test-python`
  (pytest incl. Testcontainers, `-m "not external"`)
- `make up` / `make down` (compose project `vaccine-warehouse`), `make load-reference`,
  `make generate` (`ROWS`, `SEED`), `make dbt-build`, `make reconcile`, `make smoke`,
  `make speed-check`, `make openapi` (rewrites `docs/api/openapi.json`, no Docker),
  `make demo` (up + load-reference + generate + dbt build + reconcile + smoke)
- Simulator: compose service `simulator` posts `SIMULATOR_RATE_PER_SECOND` synthetic doses per
  second (0 pauses it); `make simulate` (`RATE`, `MAX_DOSES`) runs it from the host against the
  running stack; `make speed-check` stops it while counting and starts it again on exit
- Worker: `vaccine-elt worker` (compose service `worker`, `make worker` from the host) runs queued
  jobs from `ops.job`; `vaccine-elt record-dbt-results --run-results <path> [--job-id]` copies dbt
  results into `ops`; `make dbt-build` calls it.
- Web app: `make web-install`, `make web-lint`, `make web-test`, `make web-build`; dev server
  `cd web && npm run dev` (proxies `/api` to localhost:8080); `npm run gen:api` regenerates the
  typed client from `docs/api/openapi.json` (CI fails when it drifts)
- `make smoke` and `make speed-check` get tokens from Keycloak (`vaccine-smoke`,
  `vaccine-simulator`) with the secrets in `.env`; the demo users share `KC_DEMO_PASSWORD`
- ELT and dbt by hand: `uv run --project elt vaccine-elt ...`,
  `uv run --project elt dbt <cmd> --project-dir dbt --profiles-dir dbt` with libpq `PG*` env set
  (the Makefile maps them from `.env`)
- No JDK 21 on PATH? Point `JAVA_HOME` at one; `.tools/` is gitignored for a local portable JDK.

## Repo map
- `src/main/java/io/github/emanuelluis/vaccine/`: `analytics/` (doses, coverage), `units/`
  (SCD2 history), `runs/` (ops runs), `ingest/` (POST /api/v1/doses), `security/` (resource server, roles, `ScopeGuard`, `/me`), `live/` (LISTEN hub, SSE
  stream), `dashboard/` (merged batch + live queries, `Suppression`), `reference/` (region and vaccine lists), `alerts/` (low coverage vs goals), `campaigns/` (CRUD with ETag/If-Match, progress), `exports/` (CSV, gestor only), `admin/` (data management: sources, jobs, dbt results, data-admin only), `web/` (envelope, problem details).
  `JdbcClient` + records, no JPA.
- `src/main/resources/db/migration/`: Flyway migrations (history in schema `ops`)
- `elt/src/vaccine_elt/`: `fetch.py` + `sources/` (public endpoints -> snapshots), `manifest.py`,
  `contracts.py` (typed raw contracts), `load.py` (load-reference), `runlog.py` (ops tables),
  `reconcile.py` (reconcile), `simulator.py` (live dose simulator, `simulate`),
  `worker.py` (job worker), `dbt_results.py` (run_results.json into ops)
- `elt/Dockerfile`: image for the simulator (and the data-management worker), built with the repo
  root as context; keeps the repo layout because `config.REPO_ROOT` is `parents[3]`
- `data/sources/`: vendored snapshots + `manifest.json` (sha256-verified on load)
- `dbt/`: staging (incl. `stg_live__*`), `intermediate/int_health_unit_versions` (SCD2), marts,
  seeds, singular tests, `macros/live_generation.sql` (run hooks)
- `elt/src/vaccine_elt/generator/`: seeded synthetic events, lots, receipts (`generate`)
- `scripts/smoke.sh`: endpoint smoke test (`make smoke`, CI end-to-end job);
  `scripts/speed-check.sh`: live ingest, dbt absorption and reconcile (`make speed-check`)
- `keycloak/realm-vaccine.json`: realm `vaccine` (roles, clients, demo users, claims), imported by
  compose with `${ENV}` placeholders; `docs/api/openapi.json`: committed spec checked by `OpenApiSpecIT`
- `web/`: React 19 + TypeScript SPA (Vite), OIDC login via `react-oidc-context`, typed client
  `src/api/schema.ts` generated from the spec (never edit it by hand), nginx image with a runtime
  `/config.json`; UI text in pt-BR
- `compose.yaml`, `Dockerfile`, `Makefile`, `.github/workflows/ci.yml`

## Invariants
- Flyway owns all DDL; never `ddl-auto=update`, never `DROP SCHEMA` in bootstrap scripts.
  Applied migrations are immutable: add a new version instead of editing one.
- No credentials in the repo: config from env, documented in `.env.example`.
- Data is public (IBGE, CNES) or synthetic; record source URL and license in the data manifest.
  Snapshots change only through `vaccine-elt fetch` (deterministic gzip, sha256 in the manifest).
- Loads are idempotent: TRUNCATE + COPY per raw table in one transaction; `rows_read =
  rows_loaded + rows_rejected` in `ops.load_run_table`. Never read the wall clock in data paths
  (run metadata timestamps only), except ingest validation, `received_at` and the dashboard's
  "today".
- dbt owns `staging`, `intermediate`, `marts`, `reference`; Flyway owns `raw`, `ops`, `live`, `app`. dbt
  calls only `ops.close_live_generation()` (run hooks) and reads `raw.live_dose` and `ops`.
  Mart columns follow `docs/data-model.md`; change the dbt contract, the API records/queries and
  `src/test/resources/fixtures/marts.sql` in the same PR.
- The worker is the only writer of `ops.job` status; the API only inserts queued rows.
- The API reads `marts`, `ops`, `live` and `app`. It writes only `raw.live_dose` + `live.dose_daily` (one
  transaction per dose, generation protocol), `ops.ingest_reject`, `ops.job` (queued rows only) and
  `app.campaign` (gestor campaigns, optimistic locking on `version`). A missing mart answers 503,
  never 500. No endpoint returns an individual dose.
- Every data endpoint applies the caller's region scope (token claim `region_scope`) through
  `ScopeGuard` and `RegionFilter`; repositories take no raw region parameter. URL roles live in
  `SecurityConfig` only; everything not listed is denied. Small-cell suppression (k = 5, decision 52): a count of 1 to 4 is hidden
  when its geography is a municipality or health unit (never states, national or zeros), only in the API and
  exports through `dashboard/Suppression`, never in marts.
- Live doses are absorbed by dbt only through `ops.close_live_generation()` (called by the
  `on-run-start` hook of `dbt run` and `dbt build`); serving adds `live.dose_daily` rows with
  `generation > batch_generation` of the mart it reads, in the same statement. `fact_vaccination`
  and `mart_doses_daily` carry that `batch_generation`; absorption is checked by
  `marts_reconcile_with_raw` and `vaccine-elt reconcile`.

## Conventions
- English for code, docs and commits (Conventional Commits). Integration tests end in `IT`.
- Format with Spotless (palantir-java-format) before committing.
- Python 3.12, ruff line length 100 (+ B, BLE, DTZ, I, UP), mypy strict on `elt/src`; pytest
  markers `db` (Docker) and `external` (network, never in CI).
- dbt 1.12: generic test arguments under `arguments:`; marts have enforced contracts.
