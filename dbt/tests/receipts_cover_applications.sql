-- Fails (returns rows) when, for a unit and vaccine, the doses received up to the end of a quarter
-- are fewer than the doses applied up to the end of that quarter.
with applied as (

    select
        h.cnes_code,
        v.vaccine_code,
        d.year * 4 + d.quarter as quarter_seq,
        sum(f.doses) as applied

    from {{ ref('fact_vaccination') }} as f
    inner join {{ ref('dim_health_unit') }} as h on h.health_unit_key = f.health_unit_key
    inner join {{ ref('dim_vaccine') }} as v on v.vaccine_key = f.vaccine_key
    inner join {{ ref('dim_date') }} as d on d.date_key = f.date_key
    group by h.cnes_code, v.vaccine_code, d.year * 4 + d.quarter

),

received as (

    select
        h.cnes_code,
        l.vaccine_code,
        d.year * 4 + d.quarter as quarter_seq,
        sum(r.doses_received) as received

    from {{ ref('fact_vaccine_receipt') }} as r
    inner join {{ ref('dim_health_unit') }} as h on h.health_unit_key = r.health_unit_key
    inner join {{ ref('dim_vaccine_lot') }} as l on l.lot_key = r.lot_key
    inner join {{ ref('dim_date') }} as d on d.date_key = r.date_key
    group by h.cnes_code, l.vaccine_code, d.year * 4 + d.quarter

),

periods as (

    select cnes_code, vaccine_code, quarter_seq from applied
    union
    select cnes_code, vaccine_code, quarter_seq from received

),

per_quarter as (

    select
        p.cnes_code,
        p.vaccine_code,
        p.quarter_seq,
        coalesce(a.applied, 0) as applied,
        coalesce(r.received, 0) as received

    from periods as p
    left join applied as a
        on a.cnes_code = p.cnes_code
        and a.vaccine_code = p.vaccine_code
        and a.quarter_seq = p.quarter_seq
    left join received as r
        on r.cnes_code = p.cnes_code
        and r.vaccine_code = p.vaccine_code
        and r.quarter_seq = p.quarter_seq

),

cumulative as (

    select
        cnes_code,
        vaccine_code,
        quarter_seq,
        sum(applied) over w as cumulative_applied,
        sum(received) over w as cumulative_received

    from per_quarter
    window w as (partition by cnes_code, vaccine_code order by quarter_seq)

)

select
    cnes_code,
    vaccine_code,
    quarter_seq,
    cumulative_applied,
    cumulative_received

from cumulative
where cumulative_received < cumulative_applied
