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
