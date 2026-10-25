{{ config(indexes=[
    {'columns': ['municipality_code', 'vaccine_code', 'dose_label'], 'unique': true},
    {'columns': ['state_abbr']}
]) }}

-- Coverage denominator per municipality and indicator dose: the Censo 2022 population of the
-- target age scaled by the generator's sample_fraction, the same method as mart_coverage_by_state
-- (whose target_population is the sum of this one per state). The denominator does not depend on
-- the year, so the API divides merged doses by expected_population for any year. Full grid:
-- every municipality x indicator dose, zeros included (municipalities without census rows get 0).
with municipalities as (

    select
        municipality_key,
        municipality_code,
        state_abbr

    from {{ ref('dim_municipality') }}
    where municipality_code <> 0

),

indicators as (

    select
        vaccine_code,
        dose_label,
        coverage_target_age_years

    from {{ ref('dim_vaccine') }}
    where is_coverage_indicator

),

population as (

    select
        p.municipality_key,
        g.age_band_years,
        sum(p.population) as target_population

    from {{ ref('fact_population') }} as p
    inner join {{ ref('dim_demographic') }} as g on g.demographic_key = p.demographic_key
    where p.census_year = 2022
    group by p.municipality_key, g.age_band_years

),

-- Latest succeeded generate run; null when there is none (coverage_denominator_positive fails).
latest_run as (

    select sample_fraction

    from {{ source('ops', 'load_run') }}
    where command = 'generate'
        and status = 'succeeded'
        and sample_fraction is not null
    order by started_at desc
    limit 1

),

joined as (

    select
        m.municipality_code,
        m.state_abbr,
        i.vaccine_code,
        i.dose_label,
        i.coverage_target_age_years as target_age_years,
        coalesce(p.target_population, 0) as target_population,
        r.sample_fraction,
        coalesce(p.target_population, 0) * r.sample_fraction as expected_population

    from municipalities as m
    cross join indicators as i
    left join population as p
        on p.municipality_key = m.municipality_key
        and p.age_band_years = i.coverage_target_age_years
    left join latest_run as r on true

)

select
    cast(municipality_code as integer) as municipality_code,
    cast(state_abbr as char(2)) as state_abbr,
    cast(vaccine_code as text) as vaccine_code,
    cast(dose_label as text) as dose_label,
    cast(target_age_years as integer) as target_age_years,
    cast(target_population as bigint) as target_population,
    cast(sample_fraction as numeric) as sample_fraction,
    cast(expected_population as numeric) as expected_population,
    true as numerator_is_synthetic,
    cast('IBGE Censo 2022, SIDRA 9514' as text) as denominator_source

from joined
