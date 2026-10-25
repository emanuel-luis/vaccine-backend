# dbt project

Builds the Kimball warehouse on Postgres. Flyway owns `raw` and `ops`; dbt owns the rest. The
column contract is `docs/data-model.md`.

- `staging` (views): `stg_*`, one per raw table, casts and blank-to-null only.
- `intermediate` (views): `int_health_unit_versions`, the SCD2 versions of CNES units.
- `marts` (tables, enforced contracts): `dim_*` and `fact_population`. Every dimension except
  `dim_date` has an Unknown member with key `00000000-0000-0000-0000-000000000000`.
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
