-- Landing tables for public reference data (IBGE, CNES). Loaded from the vendored snapshots in
-- data/sources/ by `vaccine-elt load-reference`: each load replaces the table contents in one
-- transaction. Columns follow the normalized snapshot files; dbt staging renames nothing further.

CREATE SCHEMA IF NOT EXISTS raw;

-- IBGE Localidades: one row per municipality (7-digit IBGE code), with its state and region.
CREATE TABLE raw.ibge_municipality (
    municipality_code INTEGER  PRIMARY KEY CHECK (municipality_code BETWEEN 1000000 AND 9999999),
    municipality_name TEXT     NOT NULL,
    state_code        SMALLINT NOT NULL,
    state_abbr        CHAR(2)  NOT NULL,
    state_name        TEXT     NOT NULL,
    region_code       SMALLINT NOT NULL,
    region_abbr       TEXT     NOT NULL,
    region_name       TEXT     NOT NULL,
    run_id            UUID     NOT NULL
);

-- IBGE Censo 2022 (SIDRA table 9514): resident population by municipality, sex and single year
-- of age, ages 0-6 only.
CREATE TABLE raw.ibge_census_population (
    census_year       SMALLINT NOT NULL,
    municipality_code INTEGER  NOT NULL,
    sex               CHAR(1)  NOT NULL CHECK (sex IN ('F', 'M')),
    age_years         SMALLINT NOT NULL CHECK (age_years BETWEEN 0 AND 6),
    population        INTEGER  NOT NULL CHECK (population >= 0),
    run_id            UUID     NOT NULL,
    PRIMARY KEY (census_year, municipality_code, sex, age_years)
);

-- CNES primary-care units, one row per dated extract and unit. Several extracts make the SCD2
-- history of dim_health_unit reproducible. CNES uses 6-digit IBGE municipality codes.
CREATE TABLE raw.cnes_unit_snapshot (
    snapshot_date      DATE     NOT NULL,
    cnes_code          CHAR(7)  NOT NULL CHECK (cnes_code ~ '^[0-9]{7}$'),
    municipality_code6 INTEGER  NOT NULL CHECK (municipality_code6 BETWEEN 100000 AND 999999),
    unit_name          TEXT,
    neighborhood       TEXT,
    street             TEXT,
    unit_type_code     SMALLINT,
    is_active          BOOLEAN  NOT NULL,
    latitude           NUMERIC(9, 6),
    longitude          NUMERIC(9, 6),
    run_id             UUID     NOT NULL,
    PRIMARY KEY (snapshot_date, cnes_code)
);
CREATE INDEX ix_cnes_unit_snapshot_cnes_code ON raw.cnes_unit_snapshot (cnes_code);
