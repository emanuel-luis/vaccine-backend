-- Fails (returns rows) when, for one person and vaccine, a recorded dose is not dated after the
-- previous recorded dose, or follows the immediately preceding dose number by fewer than
-- min_interval_days. Doses before the generator window are not recorded, so gaps in the dose
-- numbers are allowed and only consecutive numbers are checked for the interval.
with doses as (

    select
        f.person_id,
        v.vaccine_code,
        v.dose_label,
        v.dose_number,
        v.min_interval_days,
        d.date_day

    from {{ ref('fact_vaccination') }} as f
    inner join {{ ref('dim_vaccine') }} as v on v.vaccine_key = f.vaccine_key
    inner join {{ ref('dim_date') }} as d on d.date_key = f.date_key

),

ordered as (

    select
        person_id,
        vaccine_code,
        dose_label,
        dose_number,
        min_interval_days,
        date_day,
        lag(date_day) over w as previous_date,
        lag(dose_number) over w as previous_dose_number

    from doses
    window w as (partition by person_id, vaccine_code order by dose_number)

)

select
    person_id,
    vaccine_code,
    dose_label,
    date_day,
    previous_date,
    min_interval_days

from ordered
where previous_date is not null
    and (
        date_day <= previous_date
        or (
            dose_number = previous_dose_number + 1
            and date_day - previous_date < coalesce(min_interval_days, 0)
        )
    )
