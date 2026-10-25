{{ config(indexes=[
    {'columns': ['date_day', 'cnes_code', 'vaccine_code', 'dose_label', 'age_band_years'], 'unique': true},
    {'columns': ['state_abbr', 'date_day']},
    {'columns': ['municipality_code', 'date_day']},
    {'columns': ['cnes_code']}
]) }}

-- Doses per local day, CNES unit, vaccine, dose and completed-year age band. The API adds
-- live.dose_daily rows with generation > batch_generation to this table, so the grain and the
-- geography match live.dose_daily. Unknown members map like the other marts: state NA,
-- municipality 0, CNES 0000000, vaccine and dose UNKNOWN, null age band. batch_generation is
-- the W of the fact it is built from (the same on every row).
select
    d.date_day,
    cast(m.state_abbr as char(2)) as state_abbr,
    m.municipality_code,
    h.cnes_code,
    v.vaccine_code,
    v.dose_label,
    g.age_band_years,
    cast(sum(f.doses) as bigint) as doses,
    cast(max(f.batch_generation) as bigint) as batch_generation

from {{ ref('fact_vaccination') }} as f
inner join {{ ref('dim_date') }} as d on d.date_key = f.date_key
inner join {{ ref('dim_municipality') }} as m on m.municipality_key = f.municipality_key
inner join {{ ref('dim_health_unit') }} as h on h.health_unit_key = f.health_unit_key
inner join {{ ref('dim_vaccine') }} as v on v.vaccine_key = f.vaccine_key
inner join {{ ref('dim_demographic') }} as g on g.demographic_key = f.demographic_key

group by
    d.date_day,
    m.state_abbr,
    m.municipality_code,
    h.cnes_code,
    v.vaccine_code,
    v.dose_label,
    g.age_band_years
