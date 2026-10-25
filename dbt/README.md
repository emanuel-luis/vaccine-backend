# dbt project

Builds the Kimball warehouse on Postgres. Flyway owns `raw` and `ops`; dbt owns the rest. The
column contract is `docs/data-model.md`.

- `staging` (views): `stg_*`, one per raw table, casts and blank-to-null only.
- `intermediate` (views): `int_health_unit_versions`, the SCD2 versions of CNES units, and
  `int_vaccinations_enriched`, each dose joined to the unit version valid on its date.
- `marts` (tables, enforced contracts): `dim_*`, `fact_population` (Censo 2022), the synthetic facts
  `fact_vaccination` and `fact_vaccine_receipt`, and the API marts `mart_doses_by_state_month`,
  `mart_doses_by_age_band` and `mart_coverage_by_state`. Every dimension except `dim_date` has an
  Unknown member with key `00000000-0000-0000-0000-000000000000`; facts fall back to it.
- `seeds` (schema `reference`): `pni_child_schedule`, `cnes_unit_types`.

Run it against a Postgres whose `raw` schema is loaded (libpq variables, no defaults for the
credentials):

```
export PGUSER=... PGPASSWORD=... PGDATABASE=...   # PGHOST and PGPORT default to localhost:5432
uv run --project elt dbt deps  --project-dir dbt --profiles-dir dbt
uv run --project elt dbt build --project-dir dbt --profiles-dir dbt
```

SCD2 assumption: only two dated CNES extracts exist, so history has at most two versions per unit.
The first version of every unit is back-dated to `dim_date_start` (2015-01-01), as if the unit
existed before the first extract; a change seen in an extract starts a version on the extract date.
A unit absent from a later extract gets an inactive version. Street, unit type and coordinates are
type 1 (latest known value), because the 2023 extract lacks type and coordinates.

The vaccination and receipt facts, the lots and every dose count in the analytics marts are
synthetic, produced by `vaccine-elt generate`. Only the population denominator is real (IBGE Censo
2022). `mart_coverage_by_state` scales that denominator by the `sample_fraction` of the latest
succeeded generate run in `ops.load_run`, so `coverage_pct` shows the method, not real coverage.
Singular tests in `tests/` check dose age windows and sequences, stock against applications,
point-in-time unit versions, and that the marts reconcile with the raw event count.
