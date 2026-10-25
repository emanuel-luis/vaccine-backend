with source as (

    select * from {{ source('raw', 'ibge_census_population') }}

),

renamed as (

    select
        cast(census_year as integer) as census_year,
        cast(municipality_code as integer) as municipality_code,
        cast(sex as text) as sex,
        cast(age_years as integer) as age_years,
        cast(population as integer) as population

    from source

)

select * from renamed
