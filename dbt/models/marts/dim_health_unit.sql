{{ config(indexes=[
    {'columns': ['health_unit_key'], 'unique': true},
    {'columns': ['cnes_code']}
]) }}

with versions as (

    select
        {{ uuid_key(['cnes_code', 'valid_from']) }} as health_unit_key,
        cnes_code,
        unit_name,
        neighborhood,
        municipality_code,
        is_active,
        street,
        cast(unit_type_code as integer) as unit_type_code,
        unit_type_label,
        latitude,
        longitude,
        valid_from,
        valid_to,
        is_current

    from {{ ref('int_health_unit_versions') }}

),

unknown_member as (

    select
        {{ unknown_key() }} as health_unit_key,
        '0000000' as cnes_code,
        'Unknown' as unit_name,
        cast(null as text) as neighborhood,
        0 as municipality_code,
        false as is_active,
        cast(null as text) as street,
        cast(null as integer) as unit_type_code,
        cast(null as text) as unit_type_label,
        cast(null as numeric(9, 6)) as latitude,
        cast(null as numeric(9, 6)) as longitude,
        cast('{{ var("dim_date_start") }}' as date) as valid_from,
        cast('9999-12-31' as date) as valid_to,
        true as is_current

),

final as (

    select * from versions
    union all
    select * from unknown_member

)

select
    health_unit_key,
    cnes_code,
    unit_name,
    neighborhood,
    municipality_code,
    is_active,
    street,
    unit_type_code,
    unit_type_label,
    latitude,
    longitude,
    valid_from,
    valid_to,
    is_current

from final
