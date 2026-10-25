{{ config(indexes=[{'columns': ['vaccine_key'], 'unique': true}]) }}

with schedule as (

    select
        {{ uuid_key(['vaccine_code', 'dose_label']) }} as vaccine_key,
        vaccine_code,
        vaccine_name,
        dose_label,
        dose_number,
        recommended_age_days,
        min_age_days,
        max_age_days,
        min_interval_days,
        is_coverage_indicator,
        coverage_target_age_years

    from {{ ref('pni_child_schedule') }}

),

unknown_member as (

    select
        {{ unknown_key() }} as vaccine_key,
        'UNKNOWN' as vaccine_code,
        'Unknown' as vaccine_name,
        'UNKNOWN' as dose_label,
        cast(null as integer) as dose_number,
        cast(null as integer) as recommended_age_days,
        cast(null as integer) as min_age_days,
        cast(null as integer) as max_age_days,
        cast(null as integer) as min_interval_days,
        false as is_coverage_indicator,
        cast(null as integer) as coverage_target_age_years

),

final as (

    select * from schedule
    union all
    select * from unknown_member

)

select
    vaccine_key,
    vaccine_code,
    vaccine_name,
    dose_label,
    dose_number,
    recommended_age_days,
    min_age_days,
    max_age_days,
    min_interval_days,
    is_coverage_indicator,
    coverage_target_age_years

from final
