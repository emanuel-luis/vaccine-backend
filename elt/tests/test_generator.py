from __future__ import annotations

from collections import Counter, defaultdict
from datetime import date, timedelta
from itertools import pairwise
from pathlib import Path

import pytest
from hypothesis import given, settings
from hypothesis import strategies as st

from vaccine_elt.generator.core import (
    LOCAL_TZ,
    Generated,
    cohort_start,
    first_business_day,
    generate,
    months_between,
    quarter_start,
)
from vaccine_elt.generator.inputs import (
    Municipality,
    ReferenceData,
    load_schedule,
    schedule_path,
)

START, END = date(2024, 1, 1), date(2025, 12, 31)
SCHEDULE = load_schedule(schedule_path())
DOSES = {(d.vaccine_code, d.dose_label): d for d in SCHEDULE}


def reference(next_snapshot: date | None = date(2026, 10, 24)) -> ReferenceData:
    municipalities = [
        Municipality(2111300, "MA", 7000, 7300),
        Municipality(3550308, "SP", 70000, 73000),
        Municipality(5300108, "DF", 18000, 18500),
    ]
    units = {
        2111300: ["0000001", "0000002"],
        3550308: ["0000003", "0000004", "0000005"],
        5300108: ["0000006"],
    }
    return ReferenceData(
        municipalities, units, national_age0=2_358_265, next_snapshot_date=next_snapshot
    )


def local_day(event_at: object) -> date:
    return event_at.astimezone(LOCAL_TZ).date()  # type: ignore[attr-defined, no-any-return]


def run(rows: int, seed: int) -> Generated:
    return generate(reference(), SCHEDULE, rows=rows, seed=seed, start=START, end=END)


def test_schedule_seed_is_consistent() -> None:
    assert len(SCHEDULE) == 27
    assert all(d.min_age_days <= d.recommended_age_days <= d.max_age_days for d in SCHEDULE)


def test_same_seed_same_rows_and_other_seed_other_rows() -> None:
    assert run(3000, 42) == run(3000, 42)
    assert run(3000, 42).events != run(3000, 7).events


@settings(max_examples=25, deadline=None)
@given(
    rows=st.integers(min_value=1, max_value=1500), seed=st.integers(min_value=0, max_value=10**6)
)
def test_generated_rows_respect_the_schedule(rows: int, seed: int) -> None:
    data = run(rows, seed)
    assert len(data.events) == rows
    assert len({e.event_id for e in data.events}) == rows
    lots = {lot.lot_number: lot for lot in data.lots}

    by_series: dict[tuple[object, str], list[tuple[int, date]]] = defaultdict(list)
    for event in data.events:
        day = local_day(event.administered_at)
        dose = DOSES[(event.vaccine_code, event.dose_label)]
        assert START <= day <= END
        assert dose.min_age_days <= (day - event.birth_date).days <= dose.max_age_days
        assert lots[event.lot_number].vaccine_code == event.vaccine_code
        assert lots[event.lot_number].manufactured_on <= day < lots[event.lot_number].expires_on
        by_series[(event.person_id, event.vaccine_code)].append((dose.dose_number, day))

    for (_, vaccine), doses in by_series.items():
        doses.sort()
        for (n1, d1), (n2, d2) in pairwise(doses):
            assert n2 > n1 and d2 > d1
            interval = next(
                d.min_interval_days
                for d in SCHEDULE
                if d.vaccine_code == vaccine and d.dose_number == n2
            )
            if n2 == n1 + 1 and interval:
                assert (d2 - d1).days >= interval


@settings(max_examples=15, deadline=None)
@given(
    rows=st.integers(min_value=1, max_value=1500), seed=st.integers(min_value=0, max_value=10**6)
)
def test_receipts_cover_each_quarters_applications(rows: int, seed: int) -> None:
    data = run(rows, seed)
    lots = {lot.lot_number: lot for lot in data.lots}
    applied = Counter(
        (e.cnes_code, e.vaccine_code, quarter_start(local_day(e.administered_at)))
        for e in data.events
    )
    received = {
        (r.cnes_code, lots[r.lot_number].vaccine_code, quarter_start(r.received_on)): r
        for r in data.receipts
    }
    assert set(received) == set(applied)
    for key, count in applied.items():
        receipt = received[key]
        assert receipt.doses_received >= count and receipt.doses_received % 10 == 0
        assert receipt.received_on.weekday() < 5


def test_sample_fraction_matches_persons_per_birth_year() -> None:
    data = run(5000, 1)
    span = (END - cohort_start(START)).days + 1
    expected = data.persons * 365.25 / span / 2_358_265
    assert float(data.sample_fraction) == pytest.approx(expected, rel=1e-9)


def test_window_crossing_a_cnes_extract_is_refused() -> None:
    with pytest.raises(ValueError, match="next CNES extract"):
        generate(reference(date(2025, 6, 1)), SCHEDULE, rows=10, seed=1, start=START, end=END)


@pytest.mark.parametrize("rows", [0, -5])
def test_rows_must_be_positive(rows: int) -> None:
    with pytest.raises(ValueError, match="rows"):
        run(rows, 1)


def test_calendar_helpers() -> None:
    assert first_business_day(2024, 6) == date(2024, 6, 3)  # 1 June 2024 is a Saturday
    assert quarter_start(date(2024, 8, 15)) == date(2024, 7, 1)
    assert cohort_start(date(2024, 2, 29)) == date(2018, 2, 28)
    assert months_between(date(2024, 1, 15), date(2024, 3, 1)) == [
        date(2024, 1, 1),
        date(2024, 2, 1),
        date(2024, 3, 1),
    ]
    assert (
        all(m.day == 1 for m in months_between(START, END))
        and len(months_between(START, END)) == 24
    )
    assert START - timedelta(days=1) < START


def test_unit_missing_from_the_extract_in_force_is_not_eligible(tmp_path: Path) -> None:
    from vaccine_elt import csvio
    from vaccine_elt.generator.inputs import load_reference_data
    from vaccine_elt.manifest import Manifest, SourceEntry, sha256_file, write_manifest
    from vaccine_elt.sources import cnes, ibge

    manifest = Manifest()

    def add(source_id: str, table: str, columns: list[str], rows: list[dict[str, str]]) -> None:
        path = tmp_path / f"{source_id}.csv.gz"
        csvio.write_snapshot(path, columns, rows)
        manifest.upsert(
            SourceEntry(
                id=source_id,
                path=path.name,
                table=table,
                publisher="test",
                source_url="https://example.org",
                documentation_url="https://example.org",
                license_note="test",
                citation="test",
                retrieved_at="2026-10-25T00:00:00Z",
                snapshot_date=rows[0].get("snapshot_date", "2026-10-25"),
                transform="none",
                columns=columns,
                rows=len(rows),
                sha256=sha256_file(path),
            )
        )

    def unit(day: str, code: str) -> dict[str, str]:
        return {
            "snapshot_date": day,
            "cnes_code": code,
            "municipality_code6": "211130",
            "unit_name": code,
            "neighborhood": "",
            "street": "",
            "unit_type_code": "2",
            "is_active": "true",
            "latitude": "",
            "longitude": "",
        }

    add(
        "m",
        "raw.ibge_municipality",
        ibge.MUNICIPALITY_COLUMNS,
        [
            {
                "municipality_code": "2111300",
                "municipality_name": "São Luís",
                "state_code": "21",
                "state_abbr": "MA",
                "state_name": "Maranhão",
                "region_code": "2",
                "region_abbr": "NE",
                "region_name": "Nordeste",
            },
        ],
    )
    add(
        "c",
        "raw.ibge_census_population",
        ibge.CENSUS_COLUMNS,
        [
            {
                "census_year": "2022",
                "municipality_code": "2111300",
                "sex": "F",
                "age_years": "0",
                "population": "100",
            },
        ],
    )
    add(
        "u2023",
        "raw.cnes_unit_snapshot",
        cnes.COLUMNS,
        [unit("2023-06-02", "0000001"), unit("2023-06-02", "0000002")],
    )
    add("u2026", "raw.cnes_unit_snapshot", cnes.COLUMNS, [unit("2026-10-24", "0000001")])
    write_manifest(tmp_path, manifest)

    early = load_reference_data(tmp_path, date(2024, 1, 1))
    late = load_reference_data(tmp_path, date(2027, 1, 1))
    assert early.units_by_municipality == {2111300: ["0000001", "0000002"]}
    assert early.next_snapshot_date == date(2026, 10, 24)
    assert late.units_by_municipality == {2111300: ["0000001"]}
    assert late.next_snapshot_date is None
