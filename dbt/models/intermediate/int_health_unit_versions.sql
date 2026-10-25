-- SCD2 versions of CNES units (rules in docs/data-model.md).
-- Grain: cnes_code x valid_from. Deterministic: depends only on the raw snapshots and vars.
with snapshots as (

    select
        snapshot_date,
        cnes_code,
        municipality_code6,
        unit_name,
        neighborhood,
        street,
        unit_type_code,
        is_active,
        latitude,
        longitude

    from {{ ref('stg_cnes__unit_snapshots') }}

),

-- CNES 6-digit code = IBGE 7-digit code without its check digit.
municipalities as (

    select
        municipality_code,
        municipality_code / 10 as municipality_code6

    from {{ ref('stg_ibge__municipalities') }}

),

-- A code with no match maps to 0, the Unknown municipality.
observations as (

    select
        s.snapshot_date,
        s.cnes_code,
        s.unit_name,
        s.neighborhood,
        coalesce(m.municipality_code, 0) as municipality_code,
        s.is_active

    from snapshots as s
    left join municipalities as m on m.municipality_code6 = s.municipality_code6

),

snapshot_dates as (

    select distinct snapshot_date from snapshots

),

first_seen as (

    select
        cnes_code,
        min(snapshot_date) as first_snapshot_date

    from snapshots
    group by cnes_code

),

-- One row per unit and extract from the unit's first appearance on, observed or not.
timeline as (

    select
        f.cnes_code,
        d.snapshot_date,
        o.snapshot_date as observed_date

    from first_seen as f
    inner join snapshot_dates as d on d.snapshot_date >= f.first_snapshot_date
    left join observations as o
        on o.cnes_code = f.cnes_code
        and o.snapshot_date = d.snapshot_date

),

carried as (

    select
        cnes_code,
        snapshot_date,
        observed_date,
        max(observed_date) over (partition by cnes_code order by snapshot_date) as last_observed_date

    from timeline

),

-- State of each unit at each extract. A missing unit keeps its last known attributes and is inactive.
states as (

    select
        c.cnes_code,
        c.snapshot_date,
        o.unit_name,
        o.neighborhood,
        o.municipality_code,
        (c.observed_date is not null and o.is_active) as is_active

    from carried as c
    inner join observations as o
        on o.cnes_code = c.cnes_code
        and o.snapshot_date = c.last_observed_date

),

compared as (

    select
        cnes_code,
        snapshot_date,
        unit_name,
        neighborhood,
        municipality_code,
        is_active,
        row_number() over w as version_seq,
        (unit_name, neighborhood, municipality_code, is_active) is distinct from (
            lag(unit_name) over w,
            lag(neighborhood) over w,
            lag(municipality_code) over w,
            lag(is_active) over w
        ) as is_changed

    from states
    window w as (partition by cnes_code order by snapshot_date)

),

versions as (

    select
        cnes_code,
        case
            when version_seq = 1 then cast('{{ var("dim_date_start") }}' as date)
            else snapshot_date
        end as valid_from,
        unit_name,
        neighborhood,
        municipality_code,
        is_active

    from compared
    where version_seq = 1 or is_changed

),

bounded as (

    select
        cnes_code,
        valid_from,
        coalesce(
            lead(valid_from) over (partition by cnes_code order by valid_from),
            cast('9999-12-31' as date)
        ) as valid_to,
        unit_name,
        neighborhood,
        municipality_code,
        is_active

    from versions

),

-- Type 1: the latest non-null value across extracts. Coordinates come from one extract as a pair.
type1 as (

    select
        cnes_code,
        (array_agg(street order by snapshot_date desc) filter (where street is not null))[1] as street,
        (array_agg(unit_type_code order by snapshot_date desc)
            filter (where unit_type_code is not null))[1] as unit_type_code,
        (array_agg(latitude order by snapshot_date desc)
            filter (where latitude is not null and longitude is not null))[1] as latitude,
        (array_agg(longitude order by snapshot_date desc)
            filter (where latitude is not null and longitude is not null))[1] as longitude

    from snapshots
    group by cnes_code

),

unit_types as (

    select
        unit_type_code,
        unit_type_label

    from {{ ref('cnes_unit_types') }}

),

final as (

    select
        b.cnes_code,
        b.valid_from,
        b.valid_to,
        (b.valid_to = cast('9999-12-31' as date)) as is_current,
        b.unit_name,
        b.neighborhood,
        b.municipality_code,
        b.is_active,
        t.street,
        t.unit_type_code,
        case
            when t.unit_type_code is null then null
            else coalesce(ut.unit_type_label, 'Other (CNES type ' || cast(t.unit_type_code as text) || ')')
        end as unit_type_label,
        t.latitude,
        t.longitude

    from bounded as b
    inner join type1 as t on t.cnes_code = b.cnes_code
    left join unit_types as ut on ut.unit_type_code = t.unit_type_code

)

select * from final
