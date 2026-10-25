{{ config(indexes=[
    {'columns': ['lot_key'], 'unique': true},
    {'columns': ['lot_number']}
]) }}

with lots as (

    select
        {{ uuid_key(['lot_number']) }} as lot_key,
        lot_number,
        vaccine_code,
        manufacturer_name,
        manufactured_on,
        expires_on

    from {{ ref('stg_synthetic__vaccine_lots') }}

),

unknown_member as (

    select
        {{ unknown_key() }} as lot_key,
        'UNKNOWN' as lot_number,
        'UNKNOWN' as vaccine_code,
        'Unknown' as manufacturer_name,
        cast(null as date) as manufactured_on,
        cast(null as date) as expires_on

),

final as (

    select * from lots
    union all
    select * from unknown_member

)

select
    lot_key,
    lot_number,
    vaccine_code,
    manufacturer_name,
    manufactured_on,
    expires_on

from final
