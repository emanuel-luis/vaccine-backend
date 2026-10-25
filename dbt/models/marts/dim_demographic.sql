{{ config(indexes=[{'columns': ['demographic_key'], 'unique': true}]) }}

with sexes as (

    select
        cast(sex as text) as sex,
        cast(sex_label as text) as sex_label

    from (values ('F', 'Female'), ('M', 'Male')) as s (sex, sex_label)

),

bands as (

    select cast(age_band_years as integer) as age_band_years
    from generate_series(0, 6) as a (age_band_years)

),

demographics as (

    select
        {{ uuid_key(['sexes.sex', 'bands.age_band_years']) }} as demographic_key,
        sexes.sex,
        sexes.sex_label,
        bands.age_band_years,
        case
            when bands.age_band_years = 0 then 'Under 1 year'
            when bands.age_band_years = 1 then '1 year'
            else cast(bands.age_band_years as text) || ' years'
        end as age_band_label

    from sexes
    cross join bands

),

unknown_member as (

    select
        {{ unknown_key() }} as demographic_key,
        'U' as sex,
        'Unknown' as sex_label,
        cast(null as integer) as age_band_years,
        'Unknown' as age_band_label

),

final as (

    select * from demographics
    union all
    select * from unknown_member

)

select
    demographic_key,
    sex,
    sex_label,
    age_band_years,
    age_band_label

from final
