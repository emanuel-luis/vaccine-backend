with source as (

    select * from {{ source('raw', 'ibge_municipality') }}

),

renamed as (

    select
        cast(municipality_code as integer) as municipality_code,
        cast(municipality_name as text) as municipality_name,
        cast(state_code as integer) as state_code,
        cast(state_abbr as text) as state_abbr,
        cast(state_name as text) as state_name,
        cast(region_code as integer) as region_code,
        cast(region_abbr as text) as region_abbr,
        cast(region_name as text) as region_name

    from source

)

select * from renamed
