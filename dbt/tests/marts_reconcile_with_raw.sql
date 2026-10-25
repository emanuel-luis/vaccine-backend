-- Fails (returns a row) when the doses in the fact and the marts do not match the raw rows: the
-- generator events plus the live doses with generation <= the fact's own batch_generation (not the
-- newest closed one, so a standalone `dbt test` stays exact after a later close). A non-empty
-- fact must carry exactly one batch_generation, and mart_doses_daily must carry the same one.
with fact as (

    select
        count(*) as row_count,
        count(distinct batch_generation) as generations,
        max(batch_generation) as batch_generation

    from {{ ref('fact_vaccination') }}

),

watermark as (

    select batch_generation from {{ ref('stg_live__generation_watermark') }}

),

expected as (

    select
        (select count(*) from {{ source('raw', 'vaccination_event') }})
        + (
            select count(*)
            from {{ source('raw', 'live_dose') }} as l
            where l.generation <= coalesce(
                (select batch_generation from fact), (select batch_generation from watermark)
            )
        ) as row_count

),

by_state_month as (

    select coalesce(sum(doses), 0) as doses from {{ ref('mart_doses_by_state_month') }}

),

by_age_band as (

    select coalesce(sum(doses), 0) as doses from {{ ref('mart_doses_by_age_band') }}

),

daily as (

    select
        coalesce(sum(doses), 0) as doses,
        count(distinct batch_generation) as generations,
        max(batch_generation) as batch_generation

    from {{ ref('mart_doses_daily') }}

)

select
    expected.row_count as expected_row_count,
    fact.row_count as fact_row_count,
    fact.generations as fact_generations,
    by_state_month.doses as state_month_doses,
    by_age_band.doses as age_band_doses,
    daily.doses as daily_doses,
    fact.batch_generation as fact_batch_generation,
    daily.batch_generation as daily_batch_generation

from expected
cross join fact
cross join by_state_month
cross join by_age_band
cross join daily
where fact.row_count <> expected.row_count
    or by_state_month.doses <> expected.row_count
    or by_age_band.doses <> expected.row_count
    or daily.doses <> expected.row_count
    or (fact.row_count > 0 and fact.generations <> 1)
    or (daily.doses > 0 and daily.batch_generation is distinct from fact.batch_generation)
