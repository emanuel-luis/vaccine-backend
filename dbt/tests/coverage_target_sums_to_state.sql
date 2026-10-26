-- Fails (returns rows) when the municipal denominators do not add up to the state coverage mart:
-- same states, same target_population, same sample_fraction and expected_population.
with municipal as (

    select
        state_abbr,
        vaccine_code,
        dose_label,
        sum(target_population) as target_population,
        min(sample_fraction) as sample_fraction,
        sum(expected_population) as expected_population

    from {{ ref('mart_coverage_target_by_municipality') }}
    group by state_abbr, vaccine_code, dose_label

),

state as (

    select distinct
        state_abbr,
        vaccine_code,
        dose_label,
        target_population,
        sample_fraction,
        expected_population

    from {{ ref('mart_coverage_by_state') }}

)

select
    coalesce(s.state_abbr, m.state_abbr) as state_abbr,
    coalesce(s.vaccine_code, m.vaccine_code) as vaccine_code,
    coalesce(s.dose_label, m.dose_label) as dose_label,
    s.target_population as state_target_population,
    m.target_population as municipal_target_population

from state as s
full outer join municipal as m
    on m.state_abbr = s.state_abbr
    and m.vaccine_code = s.vaccine_code
    and m.dose_label = s.dose_label
where s.state_abbr is null
    or m.state_abbr is null
    or s.target_population is distinct from m.target_population
    or s.sample_fraction is distinct from m.sample_fraction
    or abs(s.expected_population - m.expected_population) > 0.000001
