-- Fails (returns rows) when a unit's versions overlap, leave a gap, do not start at dim_date_start,
-- do not end at 9999-12-31, or when is_current is not set on exactly the last version.
with versions as (

    select
        cnes_code,
        valid_from,
        valid_to,
        is_current,
        lag(valid_to) over (partition by cnes_code order by valid_from) as previous_valid_to,
        row_number() over (partition by cnes_code order by valid_from) as version_seq

    from {{ ref('dim_health_unit') }}

),

violations as (

    select cnes_code, valid_from, 'empty or inverted range' as problem
    from versions
    where valid_from >= valid_to

    union all

    select cnes_code, valid_from, 'overlap or gap with previous version' as problem
    from versions
    where version_seq > 1 and valid_from <> previous_valid_to

    union all

    select cnes_code, valid_from, 'first version does not start at dim_date_start' as problem
    from versions
    where version_seq = 1 and valid_from <> cast('{{ var("dim_date_start") }}' as date)

    union all

    select cnes_code, valid_from, 'is_current disagrees with valid_to' as problem
    from versions
    where is_current <> (valid_to = cast('9999-12-31' as date))

    union all

    select cnes_code, min(valid_from) as valid_from, 'not exactly one current version' as problem
    from versions
    group by cnes_code
    having count(*) filter (where is_current) <> 1

)

select * from violations
