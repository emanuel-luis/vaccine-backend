{{ config(indexes=[
    {'columns': ['state_abbr', 'year_month', 'vaccine_code', 'dose_label'], 'unique': true}
]) }}

-- Synthetic doses per state, month, vaccine and dose. Doses at an Unknown municipality land in
-- state NA, so the total always equals fact_vaccination.
select
    cast(m.state_abbr as char(2)) as state_abbr,
    m.state_name,
    m.region_name,
    d.year_month,
    d.month_start,
    v.vaccine_code,
    v.dose_label,
    cast(sum(f.doses) as bigint) as doses

from {{ ref('fact_vaccination') }} as f
inner join {{ ref('dim_municipality') }} as m on m.municipality_key = f.municipality_key
inner join {{ ref('dim_date') }} as d on d.date_key = f.date_key
inner join {{ ref('dim_vaccine') }} as v on v.vaccine_key = f.vaccine_key

group by
    m.state_abbr,
    m.state_name,
    m.region_name,
    d.year_month,
    d.month_start,
    v.vaccine_code,
    v.dose_label
