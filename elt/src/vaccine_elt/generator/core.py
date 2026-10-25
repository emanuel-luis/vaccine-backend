"""The deterministic core: same inputs and seed, same rows. Never reads the wall clock.

Each synthetic person gets a home municipality (weighted by the census population aged 0), a home
unit, a sex (the municipality's census ratio) and a birth date such that they are 0-6 years old
during the window. They then follow the schedule: a series stops at the first dose not taken
(a seeded per-state chance of starting each series, then a fixed chance of each next dose) or when the dose can no longer be
given inside its age window. Only doses inside [start, end] are recorded."""

from __future__ import annotations

import math
import random
import uuid
from collections import Counter
from dataclasses import dataclass
from datetime import UTC, date, datetime, time, timedelta, timezone
from decimal import Decimal
from itertools import accumulate, groupby

from vaccine_elt.generator.inputs import Dose, ReferenceData

NAMESPACE = uuid.UUID("0b9f3d4e-58a1-4c55-9a0e-6f2d7c1b8e42")  # fixed: ids depend on seed only
LOCAL_TZ = timezone(timedelta(hours=-3))  # Brasília time; no daylight saving since 2019
MANUFACTURERS = ("MFR-A", "MFR-B", "MFR-C", "MFR-D", "MFR-E")
TAKE_UP_RANGE = (0.82, 0.97)  # probability of starting a series, per state
CONTINUATION = 0.96  # probability of the next dose of a started series
MEAN_LATENESS_DAYS = 12.0
WASTAGE_RANGE = (0.05, 0.15)
PACK_SIZE = 10
COHORT_YEARS = 6


@dataclass(frozen=True)
class Lot:
    lot_number: str
    vaccine_code: str
    manufacturer_name: str
    manufactured_on: date
    expires_on: date


@dataclass(frozen=True)
class Event:
    event_id: uuid.UUID
    person_id: uuid.UUID
    birth_date: date
    sex: str
    cnes_code: str
    vaccine_code: str
    dose_label: str
    lot_number: str
    administered_at: datetime


@dataclass(frozen=True)
class Receipt:
    receipt_id: uuid.UUID
    cnes_code: str
    lot_number: str
    received_on: date
    doses_received: int


@dataclass(frozen=True)
class Generated:
    lots: list[Lot]
    events: list[Event]
    receipts: list[Receipt]
    persons: int
    sample_fraction: Decimal


def quarter_of(day: date) -> int:
    return (day.month - 1) // 3 + 1


def quarter_start(day: date) -> date:
    return date(day.year, 3 * quarter_of(day) - 2, 1)


def first_business_day(year: int, month: int) -> date:
    day = date(year, month, 1)
    while day.weekday() >= 5:
        day += timedelta(days=1)
    return day


def cohort_start(start: date) -> date:
    """Earliest birth date of a child still under 7 on `start`."""
    try:
        return start.replace(year=start.year - COHORT_YEARS)
    except ValueError:  # 29 February
        return start.replace(year=start.year - COHORT_YEARS, day=28)


def months_between(start: date, end: date) -> list[date]:
    """First day of every month that overlaps [start, end]."""
    months, month = [], start.replace(day=1)
    while month <= end:
        months.append(month)
        month = (month + timedelta(days=32)).replace(day=1)
    return months


def _lots(
    vaccines: list[str], start: date, end: date, rng: random.Random
) -> dict[tuple[str, int, int], Lot]:
    """One synthetic lot per vaccine and calendar quarter of the window."""
    quarters = sorted({(m.year, quarter_of(m)) for m in months_between(start, end)})
    lots = {}
    for vaccine in vaccines:
        for year, quarter in quarters:
            made = date(year, 3 * quarter - 2, 1) - timedelta(days=90)
            lots[(vaccine, year, quarter)] = Lot(
                lot_number=f"{vaccine}-{year}Q{quarter}",
                vaccine_code=vaccine,
                manufacturer_name=rng.choice(MANUFACTURERS),
                manufactured_on=made,
                expires_on=made + timedelta(days=730),
            )
    return lots


def generate(
    reference: ReferenceData,
    schedule: list[Dose],
    *,
    rows: int,
    seed: int,
    start: date,
    end: date,
) -> Generated:
    if rows <= 0:
        raise ValueError("rows must be positive")
    if end < start:
        raise ValueError("end must not be before start")
    if reference.next_snapshot_date is not None and end >= reference.next_snapshot_date:
        raise ValueError(
            f"the window must end before {reference.next_snapshot_date} (next CNES extract): unit "
            "states would change inside it"
        )
    if not reference.municipalities:
        raise ValueError("no municipality has an eligible unit and a census population")

    rng = random.Random(seed)
    states = sorted({m.state_abbr for m in reference.municipalities})
    take_up = {state: rng.uniform(*TAKE_UP_RANGE) for state in states}
    vaccines = sorted({d.vaccine_code for d in schedule})
    lots = _lots(vaccines, start, end, rng)
    series = [list(doses) for _, doses in groupby(schedule, key=lambda d: d.vaccine_code)]

    municipalities = reference.municipalities
    cumulative = list(accumulate(m.age0 for m in municipalities))
    births_from = cohort_start(start)
    birth_span = (end - births_from).days + 1

    events: list[Event] = []
    persons = 0
    while len(events) < rows:
        persons += 1
        home = rng.choices(municipalities, cum_weights=cumulative)[0]
        unit = rng.choice(reference.units_by_municipality[home.code])
        sex = "F" if rng.random() < home.age0_female / home.age0 else "M"
        birth = births_from + timedelta(days=rng.randrange(birth_span))
        person_id = uuid.uuid5(NAMESPACE, f"{seed}:person:{persons}")
        base = take_up[home.state_abbr]
        for doses in series:
            previous_age: int | None = None
            for dose in doses:
                chance = base if previous_age is None else CONTINUATION
                if rng.random() > chance:
                    break
                age = dose.recommended_age_days + round(rng.expovariate(1 / MEAN_LATENESS_DAYS))
                lower = dose.min_age_days
                if previous_age is not None:
                    lower = max(lower, previous_age + (dose.min_interval_days or 1))
                age = max(age, lower)
                if age > dose.max_age_days:
                    break
                previous_age = age
                day = birth + timedelta(days=age)
                if not start <= day <= end:
                    continue
                minute = rng.randrange(8 * 60, 17 * 60)
                local = datetime.combine(day, time(minute // 60, minute % 60), tzinfo=LOCAL_TZ)
                events.append(
                    Event(
                        event_id=uuid.uuid5(
                            NAMESPACE, f"{seed}:{persons}:{dose.vaccine_code}:{dose.dose_label}"
                        ),
                        person_id=person_id,
                        birth_date=birth,
                        sex=sex,
                        cnes_code=unit,
                        vaccine_code=dose.vaccine_code,
                        dose_label=dose.dose_label,
                        lot_number=lots[(dose.vaccine_code, day.year, quarter_of(day))].lot_number,
                        administered_at=local.astimezone(UTC),
                    )
                )
    events = events[:rows]

    # One shipment per unit, vaccine and quarter (the lot's quarter), received on the quarter's
    # first business day: the quarter's applications plus seeded wastage, in whole packs.
    applied = Counter(
        (e.cnes_code, e.vaccine_code, quarter_start(e.administered_at.astimezone(LOCAL_TZ).date()))
        for e in events
    )
    receipts = []
    for (cnes_code, vaccine, quarter), count in sorted(applied.items()):
        wasted = count * (1 + rng.uniform(*WASTAGE_RANGE))
        receipts.append(
            Receipt(
                receipt_id=uuid.uuid5(NAMESPACE, f"{seed}:receipt:{cnes_code}:{vaccine}:{quarter}"),
                cnes_code=cnes_code,
                lot_number=lots[(vaccine, quarter.year, quarter_of(quarter))].lot_number,
                received_on=first_business_day(quarter.year, quarter.month),
                doses_received=math.ceil(wasted / PACK_SIZE) * PACK_SIZE,
            )
        )

    persons_per_birth_year = Decimal(persons) * Decimal("365.25") / Decimal(birth_span)
    sample_fraction = (persons_per_birth_year / Decimal(reference.national_age0)).quantize(
        Decimal("1e-12")
    )
    return Generated(
        lots=sorted(lots.values(), key=lambda lot: lot.lot_number),
        events=events,
        receipts=receipts,
        persons=persons,
        sample_fraction=sample_fraction,
    )
