-- One row per dose, with the CNES unit version valid on the dose date (point in time) and the
-- age at the dose. A dose whose unit has no version on that date keeps null unit columns.
with events as (

    select
        event_id,
        person_id,
        birth_date,
        sex,
        cnes_code,
        vaccine_code,
        dose_label,
        lot_number,
        administered_at,
        administered_on

    from {{ ref('stg_synthetic__vaccination_events') }}

),

unit_versions as (

    select
        cnes_code,
        valid_from,
        valid_to,
        municipality_code

    from {{ ref('int_health_unit_versions') }}

)

select
    e.event_id,
    e.person_id,
    e.birth_date,
    e.sex,
    e.cnes_code,
    e.vaccine_code,
    e.dose_label,
    e.lot_number,
    e.administered_at,
    e.administered_on,
    u.valid_from as unit_valid_from,
    u.municipality_code,
    e.administered_on - e.birth_date as age_days,
    cast(extract(year from age(e.administered_on, e.birth_date)) as integer) as age_years

from events as e
left join unit_versions as u
    on u.cnes_code = e.cnes_code
    and u.valid_from <= e.administered_on
    and e.administered_on < u.valid_to
