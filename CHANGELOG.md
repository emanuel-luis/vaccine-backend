# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

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
