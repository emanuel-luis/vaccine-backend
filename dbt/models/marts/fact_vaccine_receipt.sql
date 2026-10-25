{{ config(indexes=[
    {'columns': ['receipt_id'], 'unique': true},
    {'columns': ['date_key']},
    {'columns': ['lot_key']},
    {'columns': ['health_unit_key']},
    {'columns': ['municipality_key']}
]) }}

-- Grain: one lot shipment received by one unit on one date. Synthetic data.
with receipts as (

    select
        receipt_id,
        cnes_code,
        lot_number,
        received_on,
        doses_received

    from {{ ref('stg_synthetic__vaccine_receipts') }}

),

lots as (

    select lot_key, lot_number from {{ ref('dim_vaccine_lot') }}

),

units as (

    select
        health_unit_key,
        cnes_code,
        municipality_code,
        valid_from,
        valid_to

    from {{ ref('dim_health_unit') }}

),

municipalities as (

    select municipality_key, municipality_code from {{ ref('dim_municipality') }}

),

-- Unit version valid on the receipt date; a lookup that misses points to the Unknown member.
final as (

    select
        r.receipt_id,
        cast(to_char(r.received_on, 'YYYYMMDD') as integer) as date_key,
        coalesce(l.lot_key, {{ unknown_key() }}) as lot_key,
        coalesce(u.health_unit_key, {{ unknown_key() }}) as health_unit_key,
        coalesce(m.municipality_key, {{ unknown_key() }}) as municipality_key,
        r.doses_received

    from receipts as r
    left join lots as l on l.lot_number = r.lot_number
    left join units as u
        on u.cnes_code = r.cnes_code
        and u.valid_from <= r.received_on
        and r.received_on < u.valid_to
    left join municipalities as m on m.municipality_code = u.municipality_code

)

select
    receipt_id,
    date_key,
    lot_key,
    health_unit_key,
    municipality_key,
    doses_received

from final
