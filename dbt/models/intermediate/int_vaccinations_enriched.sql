-- One row per dose, with the CNES unit version valid on the dose date (point in time) and the
-- age at the dose. A dose whose unit has no version on that date keeps null unit columns.
-- Generator events plus the live doses of the closed generations (generation <= W). W is read once
-- in the watermark CTE and stamped on every row, so a table built from this model from one
-- statement carries the W that matches the live rows it absorbed.
with watermark as (

    select batch_generation from {{ ref('stg_live__generation_watermark') }}

),

events as (

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
        administered_on,
        'generator' as record_source

    from {{ ref('stg_synthetic__vaccination_events') }}

    union all

    select
        l.event_id,
        l.person_id,
        l.birth_date,
        l.sex,
        l.cnes_code,
        l.vaccine_code,
        l.dose_label,
        l.lot_number,
        l.administered_at,
        l.administered_on,
        'live' as record_source

    from {{ ref('stg_live__doses') }} as l
    cross join watermark as w
    where l.generation <= w.batch_generation

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
    e.record_source,
    w.batch_generation,
    u.valid_from as unit_valid_from,
    u.municipality_code,
    e.administered_on - e.birth_date as age_days,
    cast(extract(year from age(e.administered_on, e.birth_date)) as integer) as age_years

from events as e
cross join watermark as w
left join unit_versions as u
    on u.cnes_code = e.cnes_code
    and u.valid_from <= e.administered_on
    and e.administered_on < u.valid_to
