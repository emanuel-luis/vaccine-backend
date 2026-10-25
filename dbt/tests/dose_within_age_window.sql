-- Fails (returns a row) when a dose was given outside the age window of its schedule entry.
-- Doses that fell back to the Unknown vaccine have null limits and are not checked here.
select
    f.vaccination_id,
    v.vaccine_code,
    v.dose_label,
    f.age_days,
    v.min_age_days,
    v.max_age_days

from {{ ref('fact_vaccination') }} as f
inner join {{ ref('dim_vaccine') }} as v on v.vaccine_key = f.vaccine_key
where f.age_days < v.min_age_days
    or f.age_days > v.max_age_days
