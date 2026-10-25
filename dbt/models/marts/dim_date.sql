{{ config(indexes=[{'columns': ['date_key'], 'unique': true}]) }}

-- English month and day names come from arrays, not to_char, so they do not depend on lc_time.
with spine as (

    {{ dbt_utils.date_spine(
        datepart='day',
        start_date="cast('" ~ var('dim_date_start') ~ "' as date)",
        end_date="cast('" ~ var('dim_date_end') ~ "' as date) + 1"
    ) }}

),

days as (

    select cast(date_day as date) as date_day from spine

),

final as (

    select
        cast(to_char(date_day, 'YYYYMMDD') as integer) as date_key,
        date_day,
        cast(extract(year from date_day) as integer) as year,
        cast(extract(quarter from date_day) as integer) as quarter,
        cast(extract(month from date_day) as integer) as month,
        cast((array[
            'January', 'February', 'March', 'April', 'May', 'June',
            'July', 'August', 'September', 'October', 'November', 'December'
        ])[extract(month from date_day)::integer] as text) as month_name,
        to_char(date_day, 'YYYY-MM') as year_month,
        cast(date_trunc('month', date_day) as date) as month_start,
        cast(extract(day from date_day) as integer) as day_of_month,
        cast(extract(isodow from date_day) as integer) as iso_day_of_week,
        cast((array[
            'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'
        ])[extract(isodow from date_day)::integer] as text) as day_name,
        cast(extract(week from date_day) as integer) as iso_week,
        (extract(isodow from date_day) >= 6) as is_weekend

    from days

)

select * from final
