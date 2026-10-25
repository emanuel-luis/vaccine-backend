# Decisions

| # | Date | Decision | Choice | Why |
| --- | --- | --- | --- | --- |
| 1 | 2026-10-25 | Transformation layer | dbt-postgres (dbt-core 1.12, dbt-postgres 1.11) for staging -> marts | Brief's recommendation: data tests and docs for free, and dbt is the portfolio's modeling tool |
| 2 | 2026-10-25 | Java and Spring Boot versions | Java 21 LTS + Spring Boot 4.1.2 | The brief asked for "current Spring Boot 3.x", but 3.5 left OSS support on 2026-06-30; 4.1 is supported until 2027-07. Same migration effort, no EOL on day one |
| 3 | 2026-10-25 | Schema management | Flyway; Hibernate `ddl-auto=validate` until JPA goes away; history in schema `ops`; the API is the only runner (also in compose) | Removes `ddl-auto=update` and `DROP SCHEMA`; a one-shot migrate service breaks `docker compose up --wait` |
| 4 | 2026-10-25 | Integration tests | Testcontainers 2 with `@ServiceConnection`; `*IT` classes run by failsafe in `mvn verify`, unit/slice tests by surefire | Real Postgres in tests; `make test-fast` stays Docker-free |
| 5 | 2026-10-25 | Default branch | `main` (renamed on GitHub before this refactor) | Program-wide decision |
| 6 | 2026-10-25 | Java package and Maven coordinates | `io.github.emanuelluis.vaccine`, `io.github.emanuel-luis:vaccine-backend` | The old `com.undb` namespace belongs to a third party |
| 7 | 2026-10-25 | Postgres version | `postgres:17.11-alpine` in compose and tests | Supported until 2029; same image everywhere |
| 8 | 2026-10-25 | When CI arrives | From PR1 (lint, `mvn verify`, compose smoke), extended in PR4 | Every phase PR needs a green gate before merge |
| 9 | 2026-10-25 | Warehouse design | Blueprint in [REFACTOR_PLAN.md](REFACTOR_PLAN.md): flattened geography, junk demographic dimension, lot dimension, SCD2 health units from dated extracts, API over marts with `JdbcClient` | Architect review; deterministic and testable |
| 10 | 2026-10-25 | Public source data | Vendor small normalized snapshots (about 3 MB gzipped) with a sha256 manifest; a `fetch` command refreshes them, CI never downloads | Reproducible offline builds; government endpoints are slow and capped |
| 11 | 2026-10-25 | Repo rename (`vaccine-warehouse`) | Pending: asked the owner | High impact (outward-facing) |
| 12 | 2026-10-25 | Where the ELT runs | On the host via `uv run --project elt` (Python project in `elt/`, dbt in a `dbt` dependency group), driven by `make demo` | Simpler than an ELT container: one Dockerfile, no compose profile; CI already has uv |
| 13 | 2026-10-25 | Legacy API during the refactor | Keep `/api/dimension*`, `/api/fact` and the `src/python` loaders until PR4 swaps the API | `main` keeps a working demo after every phase |
| 14 | 2026-10-25 | SCD2 tracked attributes for health units | Track name, neighborhood, municipality, active flag; street, unit type and coordinates are type 1 | The 2023 extract has no type or coordinates, and street formats differ between extracts; tracking them would version every unit |
| 15 | 2026-10-25 | Source licenses | Record attribution and mark the exact terms as "to be confirmed" in the manifest and `docs/data-sources.md` | IBGE's terms page and the CNES portal license field could not be retrieved automatically; no license is claimed that was not verified |
| 16 | 2026-10-25 | Vaccination schedule windows | Recommended ages and max ages from the Ministry calendar and NT 87/91 of 2026; other windows labelled "modeling assumption" in the seed | Minimum ages and intervals are mostly undocumented; the generator still needs bounded windows |
| 17 | 2026-10-25 | Seeds schema | dbt seeds in schema `reference` | Keeps `staging` views-only |
| 18 | 2026-10-25 | Synthetic generator | Generated offline from the vendored snapshots and the schedule seed; per-state take-up of the first dose, fixed continuation for later doses; quarterly lots and receipts; window may not cross a CNES extract | Deterministic and testable without a database; coverage near realistic levels (Penta D3 about 82% at 200k rows); facts join the unit version valid on the dose date |
| 19 | 2026-10-25 | Coverage denominator | Censo 2022 population of the target age in the state, scaled by the run's `sample_fraction` | The official indicator uses live births (SINASC), not vendored here; the method is labelled in every row |
| 20 | 2026-10-25 | API data access | `JdbcClient` + Java records over the marts; JPA, Lombok and Mustache removed | dbt rebuilds mart tables, so the API should not validate or own their schema; plain SQL keeps the contract visible |
| 21 | 2026-10-25 | API shape | `/api/v1`, `{data, meta}` envelope with source and synthetic flag, required month range of at most 36 months for dose endpoints, problem details for errors, 503 while marts are missing | Bounded responses; consumers always see that the measures are synthetic |
| 22 | 2026-10-25 | Legacy API | Removed with its loaders and CSVs; Flyway `V5` drops the legacy tables | Replaced by the warehouse; the 2023 unit list is preserved as the vendored CNES snapshot |
| 23 | 2026-10-25 | Dependency scanning | OSV-Scanner on `pom.xml` and `elt/uv.lock` (weekly and on PRs) plus grouped weekly Dependabot | One scanner for both ecosystems, no API key needed |
