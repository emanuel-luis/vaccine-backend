{{ config(indexes=[{'columns': ['municipality_key'], 'unique': true}]) }}

with municipalities as (

    select
        {{ uuid_key(['municipality_code']) }} as municipality_key,
        municipality_code,
        municipality_code / 10 as municipality_code6,
        municipality_name,
        state_code,
        state_abbr,
        state_name,
        region_code,
        region_name

    from {{ ref('stg_ibge__municipalities') }}

),

unknown_member as (

    select
        {{ unknown_key() }} as municipality_key,
        0 as municipality_code,
        0 as municipality_code6,
        'Unknown' as municipality_name,
        0 as state_code,
        'NA' as state_abbr,
        'Unknown' as state_name,
        0 as region_code,
        'Unknown' as region_name

),

final as (

    select * from municipalities
    union all
    select * from unknown_member

)

select
    municipality_key,
    municipality_code,
    municipality_code6,
    municipality_name,
    state_code,
    state_abbr,
    state_name,
    region_code,
    region_name

from final
