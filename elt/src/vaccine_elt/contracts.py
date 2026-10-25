"""Typed row contracts for the `raw` landing tables. A snapshot row either converts cleanly into
the table's column types or becomes a reject with a reason; nothing is coerced silently."""

from __future__ import annotations

import re
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from datetime import date
from decimal import Decimal, InvalidOperation

Value = int | str | bool | date | Decimal | None
Parser = Callable[[str], Value]


class RowError(ValueError):
    """A value does not satisfy its column contract."""


def integer(low: int, high: int, *, required: bool = True) -> Parser:
    def parse(raw: str) -> int | None:
        if raw == "" and not required:
            return None
        try:
            value = int(raw)
        except ValueError as exc:
            raise RowError(f"not an integer: {raw!r}") from exc
        if not low <= value <= high:
            raise RowError(f"{value} outside [{low}, {high}]")
        return value

    return parse


def text(*, required: bool = True, pattern: str | None = None) -> Parser:
    compiled = re.compile(pattern) if pattern else None

    def parse(raw: str) -> str | None:
        value = raw.strip()
        if not value:
            if required:
                raise RowError("required value is empty")
            return None
        if compiled and not compiled.fullmatch(value):
            raise RowError(f"{value!r} does not match {compiled.pattern}")
        return value

    return parse


def boolean(raw: str) -> bool:
    if raw in ("true", "false"):
        return raw == "true"
    raise RowError(f"not a boolean: {raw!r}")


def iso_date(raw: str) -> date:
    try:
        return date.fromisoformat(raw)
    except ValueError as exc:
        raise RowError(f"not an ISO date: {raw!r}") from exc


def decimal(low: Decimal, high: Decimal) -> Parser:
    def parse(raw: str) -> Decimal | None:
        if raw == "":
            return None
        try:
            value = Decimal(raw)
        except InvalidOperation as exc:
            raise RowError(f"not a number: {raw!r}") from exc
        if not low <= value <= high:
            raise RowError(f"{value} outside [{low}, {high}]")
        return value

    return parse


@dataclass(frozen=True)
class Contract:
    table: str
    columns: tuple[tuple[str, Parser], ...]
    key: tuple[str, ...]

    @property
    def column_names(self) -> list[str]:
        return [name for name, _ in self.columns]

    def convert(self, row: Mapping[str, str]) -> dict[str, Value]:
        converted: dict[str, Value] = {}
        for name, parse in self.columns:
            try:
                converted[name] = parse(row.get(name, ""))
            except RowError as exc:
                raise RowError(f"{name}: {exc}") from exc
        return converted


MUNICIPALITY = Contract(
    table="raw.ibge_municipality",
    columns=(
        ("municipality_code", integer(1_000_000, 9_999_999)),
        ("municipality_name", text()),
        ("state_code", integer(11, 53)),
        ("state_abbr", text(pattern=r"[A-Z]{2}")),
        ("state_name", text()),
        ("region_code", integer(1, 5)),
        ("region_abbr", text()),
        ("region_name", text()),
    ),
    key=("municipality_code",),
)

CENSUS_POPULATION = Contract(
    table="raw.ibge_census_population",
    columns=(
        ("census_year", integer(1900, 2100)),
        ("municipality_code", integer(1_000_000, 9_999_999)),
        ("sex", text(pattern=r"[FM]")),
        ("age_years", integer(0, 6)),
        ("population", integer(0, 10_000_000)),
    ),
    key=("census_year", "municipality_code", "sex", "age_years"),
)

CNES_UNIT_SNAPSHOT = Contract(
    table="raw.cnes_unit_snapshot",
    columns=(
        ("snapshot_date", iso_date),
        ("cnes_code", text(pattern=r"[0-9]{7}")),
        ("municipality_code6", integer(100_000, 999_999)),
        ("unit_name", text(required=False)),
        ("neighborhood", text(required=False)),
        ("street", text(required=False)),
        ("unit_type_code", integer(0, 999, required=False)),
        ("is_active", boolean),
        ("latitude", decimal(Decimal(-90), Decimal(90))),
        ("longitude", decimal(Decimal(-180), Decimal(180))),
    ),
    key=("snapshot_date", "cnes_code"),
)

REFERENCE_CONTRACTS = (MUNICIPALITY, CENSUS_POPULATION, CNES_UNIT_SNAPSHOT)
