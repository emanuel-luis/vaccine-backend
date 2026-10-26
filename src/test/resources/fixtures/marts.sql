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
    ('11111111-1111-1111-1111-111111111111', 2111300, 211130, 'São Luís', 21, 'MA', 'Maranhão', 2, 'Nordeste'),
    (gen_random_uuid(), 2105302, 210530, 'Imperatriz', 21, 'MA', 'Maranhão', 2, 'Nordeste'),
    (gen_random_uuid(), 2927408, 292740, 'Salvador', 29, 'BA', 'Bahia', 2, 'Nordeste'),
    (gen_random_uuid(), 2910800, 291080, 'Feira de Santana', 29, 'BA', 'Bahia', 2, 'Nordeste'),
    (gen_random_uuid(), 3550308, 355030, 'São Paulo', 35, 'SP', 'São Paulo', 3, 'Sudeste');

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
     '2026-10-24', '9999-12-31', true),
    ('22222222-2222-2222-2222-222222222223', '0000001', 'USF INATIVA', 'CENTRO', 2111300, false,
     null, null, null, null, null, '2015-01-01', '9999-12-31', true),
    ('22222222-2222-2222-2222-222222222224', '0000002', 'USF SEM MUNICIPIO', 'CENTRO', 0, true,
     null, null, null, null, null, '2015-01-01', '9999-12-31', true),
    (gen_random_uuid(), '0000100', 'UBS SALVADOR A', 'CENTRO', 2927408, true,
     'RUA B - 1', 2, 'Centro de saúde / unidade básica', -12.970000, -38.500000,
     '2015-01-01', '9999-12-31', true),
    (gen_random_uuid(), '0000101', 'UBS SALVADOR B', 'CENTRO', 2927408, true,
     null, null, null, null, null, '2015-01-01', '9999-12-31', true),
    (gen_random_uuid(), '0000102', 'UBS SALVADOR INATIVA', 'CENTRO', 2927408, false,
     null, null, null, null, null, '2015-01-01', '9999-12-31', true),
    (gen_random_uuid(), '0000200', 'UBS FEIRA', 'CENTRO', 2910800, true,
     null, null, null, null, null, '2015-01-01', '9999-12-31', true);

create table marts.dim_vaccine (
    vaccine_key uuid, vaccine_code text, vaccine_name text, dose_label text, dose_number integer,
    recommended_age_days integer, min_age_days integer, max_age_days integer, min_interval_days integer,
    is_coverage_indicator boolean, coverage_target_age_years integer, coverage_goal_pct numeric(5, 2),
    goal_source text
);
insert into marts.dim_vaccine values
    (gen_random_uuid(), 'PENTA', 'Pentavalente', 'D1', 1, 60, 42, 730, 0, false, null, null, null),
    (gen_random_uuid(), 'PENTA', 'Pentavalente', 'D2', 2, 120, 42, 730, 30, false, null, null, null),
    (gen_random_uuid(), 'PENTA', 'Pentavalente', 'D3', 3, 180, 42, 730, 30, true, 1, 95, 'fixture goal'),
    (gen_random_uuid(), 'ROTA', 'Rotavirus', 'D1', 1, 60, 42, 105, 0, false, null, null, null),
    (gen_random_uuid(), 'ROTA', 'Rotavirus', 'D2', 2, 120, 70, 240, 30, true, 1, 90, 'fixture goal'),
    (gen_random_uuid(), 'BCG', 'BCG', 'D1', 1, 0, 0, null, 0, false, null, null, null);

create table marts.dim_vaccine_lot (
    lot_key uuid, lot_number text, vaccine_code text, manufacturer_name text, manufactured_on date,
    expires_on date
);
insert into marts.dim_vaccine_lot values
    (gen_random_uuid(), 'LOT-PENTA-OK', 'PENTA', 'Fab A', '2026-01-01', '2027-12-31'),
    (gen_random_uuid(), 'LOT-PENTA-OLD', 'PENTA', 'Fab A', '2025-01-01', '2026-10-01'),
    (gen_random_uuid(), 'LOT-ROTA-OK', 'ROTA', 'Fab B', '2026-01-01', '2027-12-31');

-- Batch layer of the dashboards (dbt contract: mart_doses_daily). batch_generation is the same on
-- every row: the generation the build absorbed (0 = no live dose absorbed).
create table marts.mart_doses_daily (
    date_day date, state_abbr char(2), municipality_code integer, cnes_code text, vaccine_code text,
    dose_label text, age_band_years integer, doses bigint, batch_generation bigint
);
insert into marts.mart_doses_daily values
    ('2026-10-20', 'MA', 2111300, '0000264', 'PENTA', 'D1', 0, 10, 0),
    ('2026-10-24', 'MA', 2111300, '0000264', 'PENTA', 'D2', 0, 5, 0),
    ('2026-10-24', 'SP', 3550308, '0000999', 'ROTA', 'D1', null, 30, 0),
    -- September rows feed DashboardIT (coverage, map, units, suppression); October stays as above.
    ('2026-09-10', 'BA', 2927408, '0000100', 'PENTA', 'D3', 0, 40, 0),
    ('2026-09-10', 'BA', 2927408, '0000100', 'PENTA', 'D3', 1, 10, 0),
    ('2026-09-11', 'BA', 2927408, '0000100', 'PENTA', 'D3', 3, 7, 0),
    ('2026-09-11', 'BA', 2927408, '0000101', 'PENTA', 'D3', 0, 3, 0),
    ('2026-09-12', 'BA', 2910800, '0000200', 'PENTA', 'D3', 0, 4, 0),
    ('2026-09-12', 'MA', 2105302, '0000300', 'PENTA', 'D3', 0, 6, 0),
    ('2026-09-12', 'MA', 2111300, '0000264', 'PENTA', 'D3', 0, 2, 0),
    ('2026-09-13', 'BA', 2927408, '0000100', 'ROTA', 'D2', 0, 5, 0);

-- Coverage denominators (dbt contract: mart_coverage_target_by_municipality); Imperatriz has none,
-- São Paulo has no row at all.
create table marts.mart_coverage_target_by_municipality (
    municipality_code integer, state_abbr char(2), vaccine_code text, dose_label text,
    target_age_years integer, target_population bigint, sample_fraction numeric,
    expected_population numeric, numerator_is_synthetic boolean, denominator_source text
);
insert into marts.mart_coverage_target_by_municipality values
    (2927408, 'BA', 'PENTA', 'D3', 1, 10000, 0.01, 100, true, 'IBGE Censo 2022, SIDRA 9514'),
    (2927408, 'BA', 'ROTA', 'D2', 1, 10000, 0.01, 100, true, 'IBGE Censo 2022, SIDRA 9514'),
    (2910800, 'BA', 'PENTA', 'D3', 1, 4000, 0.01, 40, true, 'IBGE Censo 2022, SIDRA 9514'),
    (2105302, 'MA', 'PENTA', 'D3', 1, 0, 0.01, 0, true, 'IBGE Censo 2022, SIDRA 9514'),
    (2111300, 'MA', 'PENTA', 'D3', 1, 800, 0.01, 8, true, 'IBGE Censo 2022, SIDRA 9514');

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

-- Speed layer back to its initial state: every test starts at generation 1 with no live rows.
truncate raw.live_dose, live.dose_daily, ops.ingest_reject, ops.live_generation_close;
update ops.live_generation set generation = 1;
