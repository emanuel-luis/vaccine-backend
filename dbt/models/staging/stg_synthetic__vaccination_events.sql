-- administered_on is the local calendar date (Brasilia time) of the UTC timestamp.
select
    cast(event_id as uuid) as event_id,
    cast(person_id as uuid) as person_id,
    cast(birth_date as date) as birth_date,
    cast(sex as text) as sex,
    cast(cnes_code as text) as cnes_code,
    cast(vaccine_code as text) as vaccine_code,
    cast(dose_label as text) as dose_label,
    cast(lot_number as text) as lot_number,
    cast(administered_at as timestamptz) as administered_at,
    cast(administered_at at time zone 'America/Sao_Paulo' as date) as administered_on

from {{ source('raw', 'vaccination_event') }}
