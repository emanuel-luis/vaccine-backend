-- Fails (returns a row) when the fact loses or invents population or rows versus the raw table.
with fact as (

    select
        sum(population) as total_population,
        count(*) as row_count

    from {{ ref('fact_population') }}

),

raw as (

    select
        sum(population) as total_population,
        count(*) as row_count

    from {{ source('raw', 'ibge_census_population') }}

)

select
    fact.total_population as fact_total_population,
    raw.total_population as raw_total_population,
    fact.row_count as fact_row_count,
    raw.row_count as raw_row_count

from fact
cross join raw
where fact.total_population is distinct from raw.total_population
    or fact.row_count <> raw.row_count
