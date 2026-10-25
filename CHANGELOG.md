# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

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
