-- Fails (returns rows) when a dose points to a unit version that is the Unknown member, is missing,
-- is not valid on the dose date, or belongs to a different CNES code than the raw event.
select
    f.vaccination_id,
    d.date_day,
    e.cnes_code as event_cnes_code,
    h.cnes_code as version_cnes_code,
    h.valid_from,
    h.valid_to

from {{ ref('fact_vaccination') }} as f
inner join {{ ref('dim_date') }} as d on d.date_key = f.date_key
inner join {{ ref('stg_synthetic__vaccination_events') }} as e on e.event_id = f.vaccination_id
left join {{ ref('dim_health_unit') }} as h on h.health_unit_key = f.health_unit_key
where h.health_unit_key is null
    or h.health_unit_key = {{ unknown_key() }}
    or d.date_day < h.valid_from
    or d.date_day >= h.valid_to
    or h.cnes_code is distinct from e.cnes_code
