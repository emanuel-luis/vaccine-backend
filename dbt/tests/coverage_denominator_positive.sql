-- Fails (returns rows) when a coverage row has no positive population, sample fraction or expected
-- population. A null sample_fraction means no succeeded generate run was found in ops.load_run.
select
    state_abbr,
    year,
    vaccine_code,
    dose_label,
    target_population,
    sample_fraction,
    expected_population

from {{ ref('mart_coverage_by_state') }}
where target_population is null
    or target_population <= 0
    or sample_fraction is null
    or sample_fraction <= 0
    or expected_population is null
    or expected_population <= 0
