{{ config(indexes=[
    {'columns': ['vaccination_id'], 'unique': true},
    {'columns': ['person_id']},
    {'columns': ['date_key']},
    {'columns': ['vaccine_key']},
    {'columns': ['lot_key']},
    {'columns': ['health_unit_key']},
    {'columns': ['municipality_key']},
    {'columns': ['demographic_key']}
]) }}

-- Grain: one dose given to one synthetic person. Synthetic data from the seeded generator and the
-- live doses of the closed generations; every row carries the same batch_generation (W).
with doses as (

    select
        event_id,
        person_id,
        sex,
        cnes_code,
        vaccine_code,
        dose_label,
        lot_number,
        administered_on,
        unit_valid_from,
        municipality_code,
        age_days,
        age_years,
        record_source,
        batch_generation

    from {{ ref('int_vaccinations_enriched') }}

),

vaccines as (

    select vaccine_key, vaccine_code, dose_label from {{ ref('dim_vaccine') }}

),

lots as (

    select lot_key, lot_number from {{ ref('dim_vaccine_lot') }}

),

units as (

    select health_unit_key, cnes_code, valid_from from {{ ref('dim_health_unit') }}

),

municipalities as (

    select municipality_key, municipality_code from {{ ref('dim_municipality') }}

),

demographics as (

    select demographic_key, sex, age_band_years from {{ ref('dim_demographic') }}

),

-- A lookup that misses points to the Unknown member, so no dose is lost.
final as (

    select
        d.event_id as vaccination_id,
        d.person_id,
        cast(to_char(d.administered_on, 'YYYYMMDD') as integer) as date_key,
        coalesce(v.vaccine_key, {{ unknown_key() }}) as vaccine_key,
        coalesce(l.lot_key, {{ unknown_key() }}) as lot_key,
        coalesce(u.health_unit_key, {{ unknown_key() }}) as health_unit_key,
        coalesce(m.municipality_key, {{ unknown_key() }}) as municipality_key,
        coalesce(g.demographic_key, {{ unknown_key() }}) as demographic_key,
        d.age_days,
        1 as doses,
        d.record_source,
        d.batch_generation

    from doses as d
    left join vaccines as v on v.vaccine_code = d.vaccine_code and v.dose_label = d.dose_label
    left join lots as l on l.lot_number = d.lot_number
    left join units as u on u.cnes_code = d.cnes_code and u.valid_from = d.unit_valid_from
    left join municipalities as m on m.municipality_code = d.municipality_code
    left join demographics as g on g.sex = d.sex and g.age_band_years = d.age_years

)

select
    vaccination_id,
    person_id,
    date_key,
    vaccine_key,
    lot_key,
    health_unit_key,
    municipality_key,
    demographic_key,
    cast(age_days as integer) as age_days,
    cast(doses as integer) as doses,
    record_source,
    cast(batch_generation as bigint) as batch_generation

from final
