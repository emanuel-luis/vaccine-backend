{{ config(indexes=[
    {'columns': ['state_abbr', 'year_month', 'vaccine_code', 'sex', 'age_band_years'], 'unique': true}
]) }}

-- Synthetic doses per state, month, vaccine, sex and completed-year age band at the dose. Doses
-- that matched no demographic member keep sex U and a null age band instead of being dropped.
select
    cast(m.state_abbr as char(2)) as state_abbr,
    d.year_month,
    d.month_start,
    v.vaccine_code,
    cast(g.sex as char(1)) as sex,
    g.age_band_years,
    cast(sum(f.doses) as bigint) as doses

from {{ ref('fact_vaccination') }} as f
inner join {{ ref('dim_municipality') }} as m on m.municipality_key = f.municipality_key
inner join {{ ref('dim_date') }} as d on d.date_key = f.date_key
inner join {{ ref('dim_vaccine') }} as v on v.vaccine_key = f.vaccine_key
inner join {{ ref('dim_demographic') }} as g on g.demographic_key = f.demographic_key

group by
    m.state_abbr,
    d.year_month,
    d.month_start,
    v.vaccine_code,
    g.sex,
    g.age_band_years
