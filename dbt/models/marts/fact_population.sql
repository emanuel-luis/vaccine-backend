{{ config(indexes=[
    {'columns': ['municipality_key']},
    {'columns': ['demographic_key']}
]) }}

with census as (

    select
        census_year,
        municipality_code,
        sex,
        age_years,
        population

    from {{ ref('stg_ibge__census_population') }}

),

municipalities as (

    select
        municipality_key,
        municipality_code

    from {{ ref('dim_municipality') }}

),

demographics as (

    select
        demographic_key,
        sex,
        age_band_years

    from {{ ref('dim_demographic') }}

),

-- A cell that matches no dimension member points to Unknown, so the total never loses rows.
final as (

    select
        coalesce(m.municipality_key, {{ unknown_key() }}) as municipality_key,
        coalesce(d.demographic_key, {{ unknown_key() }}) as demographic_key,
        c.census_year,
        c.population

    from census as c
    left join municipalities as m on m.municipality_code = c.municipality_code
    left join demographics as d on d.sex = c.sex and d.age_band_years = c.age_years

)

select
    municipality_key,
    demographic_key,
    census_year,
    population

from final
