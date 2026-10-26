# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added (web platform)
- Speed layer: dose ingest with a generation watermark, a live aggregate merged with the dbt marts
  in one statement, Postgres `LISTEN`/`NOTIFY` relayed over server-sent events, a dose simulator
  and `make speed-check`; `vaccine-elt reconcile` and a dbt reconciliation test.
- Keycloak realm with five demo users, region scoping through the `region_scope` claim, and
  small-cell suppression (k = 5) in the API and CSV exports.
- Dashboard API: summary, time series, by-vaccine and by-age-band, coverage map data, health units,
  low-coverage alerts, campaigns (ETag and `If-Match`) and gestor-only CSV exports; committed
  OpenAPI spec checked by `OpenApiSpecIT`.
- Web app (React 19, TypeScript, Vite): OIDC login, live dashboard, drill-down coverage map
  (Brazil, state, municipality, health units), alerts, campaigns, light and dark themes, a typed
  client generated from the spec and keyboard and screen-reader support.
- Data management: queued jobs (`ops.job`) claimed by a worker with `SKIP LOCKED`, dbt results
  recorded in `ops`, and screens for sources, runs with a live log, quality tests and reconciliation.
- Browser end-to-end tests (Playwright, six specs plus axe checks) that sign in through Keycloak;
  `make e2e`, `make screenshots` and a CI step that runs them after `make demo`.
- README rewrite with architecture, quickstart, demo users and screenshots.

### Changed
- The CI end-to-end job now also installs Chromium and runs the Playwright suite (timeout 40
  minutes) and uploads its report on failure.
- Repository renamed to `vaccine-warehouse` (GitHub redirects `vaccine-backend`). The Maven
  artifact (`io.github.emanuel-luis:vaccine-warehouse`), the compose project and the Spring
  application name follow.

### Added (phase 4: analytical API)
- Read-only endpoints over the marts with `JdbcClient` and Java records:
  `/api/v1/doses/by-state-month`, `/api/v1/doses/by-age-band`, `/api/v1/coverage`,
  `/api/v1/health-units/{cnesCode}/history`, `/api/v1/runs/latest`; `{data, meta}` envelope,
  validated parameters, RFC 9457 problem details (400, 404, 503 while marts are not built).
- `scripts/smoke.sh` and `make smoke`; the CI end-to-end job calls every endpoint and uploads the
  dbt docs.
- `security.yml` (OSV-Scanner on `pom.xml` and `elt/uv.lock`) and Dependabot for Maven, uv,
  GitHub Actions and Docker.

### Removed
- Legacy endpoints `/api/dimension`, `/api/dimension/render`, `/api/fact`, the Mustache page, the
  JPA entities, Lombok, the `src/python` loaders and their CSVs; Flyway `V5` drops the legacy
  tables (the 2023 unit list lives on as the vendored CNES snapshot).

### Added (phase 3: synthetic facts and analytics marts)
- `vaccine-elt generate --rows --seed --start --end`: seeded, deterministic vaccination events,
  lots and receipts following the 2026 childhood schedule, written in one transaction with the
  run's `sample_fraction` in `ops.load_run`; hypothesis property tests and a Testcontainers reload
  test.
- dbt: synthetic staging, `int_vaccinations_enriched` (point-in-time join to the unit version),
  `dim_vaccine_lot`, `fact_vaccination`, `fact_vaccine_receipt`, `mart_doses_by_state_month`,
  `mart_doses_by_age_band`, `mart_coverage_by_state`, and singular tests (age windows, dose order,
  receipts cover applications, unit version valid at date, coverage denominator, reconciliation
  with raw).
- `make generate` (`ROWS`, `SEED`); `make demo` now generates 200,000 events (CI: 20,000).

### Added (phase 2: reference data and dimensions)
- Flyway `V2`-`V4`: `ops` run metadata (runs, per-source counts, rejects) and the `raw` landing
  tables for IBGE, CNES and the synthetic events of the next phase.
- `elt/` Python package (`vaccine-elt`): `fetch` refreshes deterministic snapshots of IBGE
  Localidades, Censo 2022 (SIDRA 9514) and CNES; `load-reference` verifies them against the sha256
  manifest, validates every row and reloads each raw table in one transaction.
- Vendored snapshots in `data/sources/` with `manifest.json` (two dated CNES extracts, 2023 and
  2026).
- dbt project: staging views, `int_health_unit_versions` (SCD2), `dim_date`, `dim_municipality`,
  `dim_health_unit`, `dim_vaccine`, `dim_demographic`, `fact_population`, PNI schedule and CNES
  unit-type seeds, 66 data tests.
- Make targets `load-reference`, `dbt-deps`, `dbt-build`, `test-java`, `test-python`; `make demo`
  now loads the reference data and builds the marts.
- CI: lint runs ruff, mypy and `dbt parse`; new Python job; the end-to-end job runs `dbt build`.
- `docs/data-model.md` and `docs/data-sources.md`.

### Changed
- Java 21 and Spring Boot 4.1 (from Java 20 and Spring Boot 3.0); Jakarta namespace only.
- Java package `io.github.emanuelluis.vaccine`; Maven coordinates `io.github.emanuel-luis:vaccine-backend`.
- Database configuration comes from the environment; no credentials in the repository.
- Legacy Python loaders read the connection from libpq variables and no longer run DDL.

### Added
- Flyway migrations (`V1` baseline of the legacy schema, history in schema `ops`); Hibernate
  only validates.
- Multi-stage Dockerfile (JRE runtime, non-root, healthcheck) and `compose.yaml` (Postgres + API).
- Makefile (`lint`, `test`, `test-fast`, `up`, `down`, `seed`, `demo`), `.env.example`.
- Testcontainers integration tests and a web-slice unit test; Spotless formatting.
- GitHub Actions CI: lint, `mvn verify`, compose smoke test.
- MIT license, refactor plan and decisions log under `docs/`.

### Removed
- `DROP SCHEMA public CASCADE` bootstrap script, `ddl-auto=update`, QueryDSL, devtools and the
  `javax.persistence` / `javax.transaction` dependencies.
