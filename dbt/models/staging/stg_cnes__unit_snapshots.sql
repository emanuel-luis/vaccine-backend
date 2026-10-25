-- Casts and blank-to-null cleanup only. The 6-digit CNES municipality code is mapped to the
-- 7-digit IBGE code in int_health_unit_versions, which owns the municipality lookup.
with source as (

    select * from {{ source('raw', 'cnes_unit_snapshot') }}

),

renamed as (

    select
        cast(snapshot_date as date) as snapshot_date,
        cast(cnes_code as text) as cnes_code,
        cast(municipality_code6 as integer) as municipality_code6,
        nullif(trim(unit_name), '') as unit_name,
        nullif(trim(neighborhood), '') as neighborhood,
        nullif(trim(street), '') as street,
        cast(unit_type_code as smallint) as unit_type_code,
        cast(is_active as boolean) as is_active,
        cast(latitude as numeric(9, 6)) as latitude,
        cast(longitude as numeric(9, 6)) as longitude

    from source

)

select * from renamed
