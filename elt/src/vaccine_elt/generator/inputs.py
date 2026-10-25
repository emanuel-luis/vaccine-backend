"""Generator inputs: the vaccination schedule seed (shared with dbt) and the reference data read
from the vendored snapshots, so generation needs no database and is reproducible offline."""

from __future__ import annotations

import csv
from collections import defaultdict
from dataclasses import dataclass
from datetime import date
from pathlib import Path

from vaccine_elt import config, csvio
from vaccine_elt.manifest import read_manifest, verify


def schedule_path() -> Path:
    return config.REPO_ROOT / "dbt" / "seeds" / "pni_child_schedule.csv"


@dataclass(frozen=True)
class Dose:
    vaccine_code: str
    dose_label: str
    dose_number: int
    recommended_age_days: int
    min_age_days: int
    max_age_days: int
    min_interval_days: int | None


def load_schedule(path: Path) -> list[Dose]:
    """Doses sorted by vaccine, then dose number (the order a series is given in)."""
    with path.open(encoding="utf-8", newline="") as handle:
        doses = [
            Dose(
                vaccine_code=row["vaccine_code"],
                dose_label=row["dose_label"],
                dose_number=int(row["dose_number"]),
                recommended_age_days=int(row["recommended_age_days"]),
                min_age_days=int(row["min_age_days"]),
                max_age_days=int(row["max_age_days"]),
                min_interval_days=int(row["min_interval_days"])
                if row["min_interval_days"]
                else None,
            )
            for row in csv.DictReader(handle)
        ]
    for dose in doses:
        if not dose.min_age_days <= dose.recommended_age_days <= dose.max_age_days:
            raise ValueError(f"{dose.vaccine_code} {dose.dose_label}: inconsistent age window")
    return sorted(doses, key=lambda d: (d.vaccine_code, d.dose_number))


@dataclass(frozen=True)
class Municipality:
    code: int
    state_abbr: str
    age0_female: int
    age0_male: int

    @property
    def age0(self) -> int:
        return self.age0_female + self.age0_male


@dataclass(frozen=True)
class ReferenceData:
    municipalities: list[Municipality]
    """Municipalities with at least one eligible unit and a positive age-0 population, by code."""
    units_by_municipality: dict[int, list[str]]
    """Eligible CNES codes (active at the window start), sorted, per 7-digit municipality code."""
    national_age0: int
    """Censo 2022 population aged 0 in the whole country (denominator of `sample_fraction`)."""
    next_snapshot_date: date | None
    """First CNES extract after the window start: unit states change there."""


def _rows(directory: Path, table: str) -> list[tuple[str, csvio.Row]]:
    manifest = read_manifest(directory)
    out: list[tuple[str, csvio.Row]] = []
    for entry in sorted(manifest.sources, key=lambda e: e.id):
        if entry.table == table:
            _, rows = csvio.read_snapshot(directory / entry.path)
            out.extend((entry.snapshot_date, row) for _, row in rows)
    return out


def load_reference_data(directory: Path, window_start: date) -> ReferenceData:
    """Unit state at `window_start` = the latest extract on or before it, or the earliest extract
    when none is (the same back-dating rule as the SCD2 dimension)."""
    verify(directory, read_manifest(directory))
    code7_by_code6 = {
        int(row["municipality_code"]) // 10: int(row["municipality_code"])
        for _, row in _rows(directory, "raw.ibge_municipality")
    }
    states = {
        int(row["municipality_code"]): row["state_abbr"]
        for _, row in _rows(directory, "raw.ibge_municipality")
    }

    age0: dict[int, dict[str, int]] = defaultdict(lambda: {"F": 0, "M": 0})
    for _, row in _rows(directory, "raw.ibge_census_population"):
        if row["age_years"] == "0":
            age0[int(row["municipality_code"])][row["sex"]] += int(row["population"])

    observations: dict[str, list[tuple[date, csvio.Row]]] = defaultdict(list)
    snapshot_dates: set[date] = set()
    for snapshot_date, row in _rows(directory, "raw.cnes_unit_snapshot"):
        day = date.fromisoformat(row["snapshot_date"] or snapshot_date)
        observations[row["cnes_code"]].append((day, row))
        snapshot_dates.add(day)

    # Same rules as the SCD2 model: back-dated first extract, and a unit missing from the extract
    # in force at the window start is inactive.
    in_force = max((day for day in snapshot_dates if day <= window_start), default=None)
    units: dict[int, list[str]] = defaultdict(list)
    for cnes_code in sorted(observations):
        history = sorted(observations[cnes_code], key=lambda item: item[0])
        before = [(day, row) for day, row in history if day <= window_start]
        if before and before[-1][0] != in_force:
            continue
        state = before[-1][1] if before else history[0][1]
        municipality = code7_by_code6.get(int(state["municipality_code6"]))
        if state["is_active"] == "true" and municipality is not None:
            units[municipality].append(cnes_code)

    municipalities = [
        Municipality(code, states[code], age0[code]["F"], age0[code]["M"])
        for code in sorted(units)
        if age0[code]["F"] + age0[code]["M"] > 0
    ]
    later = sorted(day for day in snapshot_dates if day > window_start)
    return ReferenceData(
        municipalities=municipalities,
        units_by_municipality={m.code: units[m.code] for m in municipalities},
        national_age0=sum(v["F"] + v["M"] for v in age0.values()),
        next_snapshot_date=later[0] if later else None,
    )
