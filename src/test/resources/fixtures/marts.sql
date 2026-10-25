-- Minimal stand-ins for the dbt marts the API reads (columns and types per the dbt contracts and
-- docs/data-model.md). The end-to-end CI job checks the API against the real dbt-built marts.
drop schema if exists marts cascade;
create schema marts;

create table marts.dim_municipality (
    municipality_key uuid, municipality_code integer, municipality_code6 integer,
    municipality_name text, state_code integer, state_abbr text, state_name text,
    region_code integer, region_name text
);
insert into marts.dim_municipality values
    ('00000000-0000-0000-0000-000000000000', 0, 0, 'Unknown', 0, 'NA', 'Unknown', 0, 'Unknown'),
    ('11111111-1111-1111-1111-111111111111', 2111300, 211130, 'São Luís', 21, 'MA', 'Maranhão', 2, 'Nordeste');

create table marts.dim_health_unit (
    health_unit_key uuid, cnes_code text, unit_name text, neighborhood text, municipality_code integer,
    is_active boolean, street text, unit_type_code integer, unit_type_label text,
    latitude numeric(9, 6), longitude numeric(9, 6), valid_from date, valid_to date, is_current boolean
);
insert into marts.dim_health_unit values
    ('22222222-2222-2222-2222-222222222221', '0000264', 'USF MARUIM', 'CENTRO', 2111300, true,
     'RUA A - 10', 2, 'Centro de saúde / unidade básica', -2.530000, -44.300000,
     '2015-01-01', '2026-10-24', false),
    ('22222222-2222-2222-2222-222222222222', '0000264', 'USF BOM SUCESSO', 'CENTRO', 2111300, true,
     'RUA A - 10', 2, 'Centro de saúde / unidade básica', -2.530000, -44.300000,
     '2026-10-24', '9999-12-31', true);

create table marts.mart_doses_by_state_month (
    state_abbr char(2), state_name text, region_name text, year_month text, month_start date,
    vaccine_code text, dose_label text, doses bigint
);
insert into marts.mart_doses_by_state_month values
    ('MA', 'Maranhão', 'Nordeste', '2024-03', '2024-03-01', 'PENTA', 'D1', 12),
    ('MA', 'Maranhão', 'Nordeste', '2025-02', '2025-02-01', 'PENTA', 'D2', 8),
    ('SP', 'São Paulo', 'Sudeste', '2024-04', '2024-04-01', 'ROTA', 'D2', 30);

create table marts.mart_doses_by_age_band (
    state_abbr char(2), year_month text, month_start date, vaccine_code text, sex char(1),
    age_band_years integer, doses bigint
);
insert into marts.mart_doses_by_age_band values
    ('MA', '2024-03', '2024-03-01', 'PENTA', 'F', 0, 7),
    ('MA', '2024-03', '2024-03-01', 'PENTA', 'M', 0, 5),
    ('MA', '2024-03', '2024-03-01', 'PENTA', 'U', null, 1);

create table marts.mart_coverage_by_state (
    state_abbr char(2), state_name text, year integer, vaccine_code text, dose_label text,
    target_age_years integer, doses bigint, target_population bigint, sample_fraction numeric,
    expected_population numeric, coverage_pct numeric(7, 2), numerator_is_synthetic boolean,
    denominator_source text
);
insert into marts.mart_coverage_by_state values
    ('MA', 'Maranhão', 2024, 'PENTA', 'D3', 0, 90, 120000, 0.001, 120, 75.00, true,
     'IBGE Censo 2022, SIDRA 9514'),
    ('MA', 'Maranhão', 2025, 'PENTA', 'D3', 0, 100, 120000, 0.001, 120, 83.33, true,
     'IBGE Censo 2022, SIDRA 9514');

truncate ops.load_run cascade;
insert into ops.load_run (run_id, command, params, seed, sample_fraction, started_at, finished_at, status) values
    ('33333333-3333-3333-3333-333333333331', 'load-reference', '{}', null, null,
     '2026-10-25 10:00+00', '2026-10-25 10:01+00', 'succeeded'),
    ('33333333-3333-3333-3333-333333333332', 'generate', '{}', 42, 0.001,
     '2026-10-25 10:02+00', '2026-10-25 10:03+00', 'succeeded'),
    ('33333333-3333-3333-3333-333333333333', 'generate', '{}', 7, null,
     '2026-10-25 09:00+00', '2026-10-25 09:01+00', 'failed');
insert into ops.load_run_table values
    ('33333333-3333-3333-3333-333333333332', 'raw.vaccination_event', 'generator', null, 200, 200, 0,
     '2026-10-25 10:03+00');
