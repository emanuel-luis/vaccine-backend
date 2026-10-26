# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
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

### Changed
- The CI end-to-end job also installs Chromium, runs the Playwright suite (timeout 40 minutes) and
  uploads its report on failure.
- Repository renamed to `vaccine-warehouse` (GitHub redirects `vaccine-backend`). The Maven
  artifact (`io.github.emanuel-luis:vaccine-warehouse`), the compose project and the Spring
  application name follow.
- The coverage map zooms to the selected municipality and outlines it; CSV export buttons are
  labelled by dataset; chart axes and tooltips use pt-BR number separators.

### Fixed
- Sign-in requested extra scopes; the app now asks for `openid` only.
- A deep link lost its target after login; the app now returns to it.
- The MapLibre worker was served with the wrong MIME type, so the map did not render behind nginx;
  it is now an asset served as JavaScript, and `text/javascript` is gzipped.

## [0.2.0] - 2026-10-25

### Added
- Flyway migrations (`V1` baseline of the legacy schema, history in schema `ops`; `V2` to `V4`
  for `ops` run metadata and the `raw` landing tables); Hibernate only validates.
- `elt/` Python package (`vaccine-elt`): `fetch` refreshes deterministic snapshots of IBGE
  Localidades, Censo 2022 (SIDRA 9514) and CNES; `load-reference` verifies them against the sha256
  manifest, validates every row and reloads each raw table in one transaction; `generate` writes
  seeded, deterministic vaccination events, lots and receipts following the 2026 childhood
  schedule, with the run's `sample_fraction` in `ops.load_run`.
- Vendored snapshots in `data/sources/` with `manifest.json` (two dated CNES extracts, 2023 and
  2026).
- dbt project: staging views, `int_health_unit_versions` (SCD2), `int_vaccinations_enriched`
  (point-in-time join to the unit version), dimensions, `fact_vaccination`,
  `fact_vaccine_receipt`, `mart_doses_by_state_month`, `mart_doses_by_age_band`,
  `mart_coverage_by_state`, PNI schedule and CNES unit-type seeds, and data tests including age
  windows, dose order, receipts covering applications and reconciliation with raw.
- Read-only analytical API over the marts (`JdbcClient`, Java records): doses by state and month,
  doses by age band, coverage, health unit history, latest runs; `{data, meta}` envelope,
  validated parameters, RFC 9457 problem details (400, 404, 503 while marts are not built).
- Multi-stage Dockerfile (JRE runtime, non-root, healthcheck) and `compose.yaml`.
- Makefile (`lint`, `test`, `test-fast`, `up`, `down`, `generate`, `load-reference`, `dbt-build`,
  `smoke`, `demo`), `.env.example`, `scripts/smoke.sh`.
- GitHub Actions CI (lint, Java and Python tests, compose end-to-end job that uploads the dbt
  docs), `security.yml` (OSV-Scanner) and Dependabot for Maven, uv, GitHub Actions and Docker.
- MIT license, refactor plan, decisions log, `docs/data-model.md` and `docs/data-sources.md`.

### Changed
- Java 21 and Spring Boot 4.1 (from Java 20 and Spring Boot 3.0); Jakarta namespace only.
- Java package `io.github.emanuelluis.vaccine`.
- Database configuration comes from the environment; no credentials in the repository.
- `make demo` loads the reference data, generates 200,000 events (CI: 20,000) and builds the
  marts.

### Removed
- Legacy endpoints `/api/dimension`, `/api/dimension/render` and `/api/fact`, the Mustache page,
  the JPA entities, Lombok, QueryDSL, devtools, the `src/python` loaders and their CSVs; Flyway
  `V5` drops the legacy tables (the 2023 unit list lives on as the vendored CNES snapshot).
- The `DROP SCHEMA public CASCADE` bootstrap script and `ddl-auto=update`.

[Unreleased]: https://github.com/emanuel-luis/vaccine-warehouse/compare/d02a361...HEAD
[0.2.0]: https://github.com/emanuel-luis/vaccine-warehouse/compare/16fb7d9...d02a361
