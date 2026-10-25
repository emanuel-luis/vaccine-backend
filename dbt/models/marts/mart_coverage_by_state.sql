{{ config(indexes=[
    {'columns': ['state_abbr', 'year', 'vaccine_code', 'dose_label'], 'unique': true}
]) }}

-- Coverage of the childhood indicator doses per state and year. The numerator is synthetic; the
-- denominator is the Censo 2022 population of the target age, scaled by the generator's
-- sample_fraction. Full grid: every state x year in fact_vaccination x indicator dose.
with years as (

    select distinct d.year
    from {{ ref('fact_vaccination') }} as f
    inner join {{ ref('dim_date') }} as d on d.date_key = f.date_key

),

states as (

    select distinct
        state_abbr,
        state_name

    from {{ ref('dim_municipality') }}
    where municipality_code <> 0

),

indicators as (

    select
        vaccine_key,
        vaccine_code,
        dose_label,
        coverage_target_age_years

    from {{ ref('dim_vaccine') }}
    where is_coverage_indicator

),

grid as (

    select
        s.state_abbr,
        s.state_name,
        y.year,
        i.vaccine_key,
        i.vaccine_code,
        i.dose_label,
        i.coverage_target_age_years

    from states as s
    cross join years as y
    cross join indicators as i

),

-- Indicator doses given in the year to children not older than the target age at the dose (in
-- completed years). "Not older" rather than "equal": the schedule's minimum ages are day counts
-- (365, 1460), and a dose at exactly that age falls one day before the birthday when a 29 February
-- lies in between.
doses as (

    select
        m.state_abbr,
        d.year,
        f.vaccine_key,
        sum(f.doses) as doses

    from {{ ref('fact_vaccination') }} as f
    inner join {{ ref('dim_vaccine') }} as v
        on v.vaccine_key = f.vaccine_key
        and v.is_coverage_indicator
    inner join {{ ref('dim_demographic') }} as g
        on g.demographic_key = f.demographic_key
        and g.age_band_years <= v.coverage_target_age_years
    inner join {{ ref('dim_municipality') }} as m on m.municipality_key = f.municipality_key
    inner join {{ ref('dim_date') }} as d on d.date_key = f.date_key
    group by m.state_abbr, d.year, f.vaccine_key

),

population as (

    select
        m.state_abbr,
        g.age_band_years,
        sum(p.population) as target_population

    from {{ ref('fact_population') }} as p
    inner join {{ ref('dim_municipality') }} as m on m.municipality_key = p.municipality_key
    inner join {{ ref('dim_demographic') }} as g on g.demographic_key = p.demographic_key
    where p.census_year = 2022
    group by m.state_abbr, g.age_band_years

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
        g.state_abbr,
        g.state_name,
        g.year,
        g.vaccine_code,
        g.dose_label,
        g.coverage_target_age_years as target_age_years,
        coalesce(d.doses, 0) as doses,
        coalesce(p.target_population, 0) as target_population,
        r.sample_fraction,
        coalesce(p.target_population, 0) * r.sample_fraction as expected_population

    from grid as g
    left join doses as d
        on d.state_abbr = g.state_abbr
        and d.year = g.year
        and d.vaccine_key = g.vaccine_key
    left join population as p
        on p.state_abbr = g.state_abbr
        and p.age_band_years = g.coverage_target_age_years
    left join latest_run as r on true

)

select
    cast(state_abbr as char(2)) as state_abbr,
    state_name,
    cast(year as integer) as year,
    vaccine_code,
    dose_label,
    cast(target_age_years as integer) as target_age_years,
    cast(doses as bigint) as doses,
    cast(target_population as bigint) as target_population,
    cast(sample_fraction as numeric) as sample_fraction,
    cast(expected_population as numeric) as expected_population,
    cast(round(100 * doses / nullif(expected_population, 0), 2) as numeric(7, 2)) as coverage_pct,
    true as numerator_is_synthetic,
    cast('IBGE Censo 2022, SIDRA 9514' as text) as denominator_source

from joined
