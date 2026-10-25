-- Fails (returns a row) when the doses in the marts and the fact do not match the raw event rows.
with raw_events as (

    select count(*) as row_count from {{ source('raw', 'vaccination_event') }}

),

fact as (

    select count(*) as row_count from {{ ref('fact_vaccination') }}

),

by_state_month as (

    select coalesce(sum(doses), 0) as doses from {{ ref('mart_doses_by_state_month') }}

),

by_age_band as (

    select coalesce(sum(doses), 0) as doses from {{ ref('mart_doses_by_age_band') }}

)

select
    raw_events.row_count as raw_row_count,
    fact.row_count as fact_row_count,
    by_state_month.doses as state_month_doses,
    by_age_band.doses as age_band_doses

from raw_events
cross join fact
cross join by_state_month
cross join by_age_band
where fact.row_count <> raw_events.row_count
    or by_state_month.doses <> raw_events.row_count
    or by_age_band.doses <> raw_events.row_count
