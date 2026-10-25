from __future__ import annotations

from pathlib import Path

import pytest
from hypothesis import given, settings
from hypothesis import strategies as st

from vaccine_elt import csvio
from vaccine_elt.contracts import CNES_UNIT_SNAPSHOT, MUNICIPALITY, RowError
from vaccine_elt.manifest import (
    Manifest,
    ManifestError,
    SourceEntry,
    read_manifest,
    sha256_file,
    verify,
    write_manifest,
)

COLUMNS = ["code", "name"]
cell = st.text(
    alphabet=st.characters(blacklist_categories=("Cs",), blacklist_characters="\x00\r"), max_size=20
)


@settings(max_examples=50, deadline=None)
@given(st.lists(st.fixed_dictionaries({"code": cell, "name": cell}), max_size=20))
def test_snapshot_round_trip_is_lossless(
    tmp_path_factory: pytest.TempPathFactory, rows: list[dict[str, str]]
) -> None:
    path = tmp_path_factory.mktemp("snap") / "rows.csv.gz"
    assert csvio.write_snapshot(path, COLUMNS, rows) == len(rows)
    header, read = csvio.read_snapshot(path)
    assert header == COLUMNS
    assert [row for _, row in read] == rows


def test_same_rows_give_the_same_bytes(tmp_path: Path) -> None:
    rows = [{"code": "1", "name": "São Luís"}]
    first, second = tmp_path / "a.csv.gz", tmp_path / "b.csv.gz"
    csvio.write_snapshot(first, COLUMNS, rows)
    csvio.write_snapshot(second, COLUMNS, rows)
    assert sha256_file(first) == sha256_file(second)


def entry(path: str, sha256: str) -> SourceEntry:
    return SourceEntry(
        id="demo",
        path=path,
        table="raw.demo",
        publisher="test",
        source_url="https://example.org",
        documentation_url="https://example.org/docs",
        license_note="test data",
        citation="test",
        retrieved_at="2026-10-25T00:00:00Z",
        snapshot_date="2026-10-25",
        transform="none",
        columns=COLUMNS,
        rows=1,
        sha256=sha256,
    )


def test_manifest_round_trip_and_verify(tmp_path: Path) -> None:
    path = tmp_path / "demo.csv.gz"
    csvio.write_snapshot(path, COLUMNS, [{"code": "1", "name": "x"}])
    manifest = Manifest()
    manifest.upsert(entry("demo.csv.gz", sha256_file(path)))
    write_manifest(tmp_path, manifest)
    loaded = read_manifest(tmp_path)
    assert loaded == manifest
    verify(tmp_path, loaded)

    path.write_bytes(path.read_bytes() + b"tampered")
    with pytest.raises(ManifestError, match="sha256"):
        verify(tmp_path, loaded)


def test_missing_file_is_reported(tmp_path: Path) -> None:
    manifest = Manifest(sources=[entry("absent.csv.gz", "0" * 64)])
    with pytest.raises(ManifestError, match="missing"):
        verify(tmp_path, manifest)


def test_contract_converts_types() -> None:
    values = CNES_UNIT_SNAPSHOT.convert(
        {
            "snapshot_date": "2026-10-24",
            "cnes_code": "0000108",
            "municipality_code6": "260290",
            "unit_name": "",
            "neighborhood": "CENTRO",
            "street": "",
            "unit_type_code": "2",
            "is_active": "true",
            "latitude": "-8.231660",
            "longitude": "",
        }
    )
    assert values["unit_name"] is None
    assert values["unit_type_code"] == 2
    assert values["is_active"] is True
    assert values["longitude"] is None


@pytest.mark.parametrize(
    ("column", "value"),
    [
        ("municipality_code", "123"),
        ("state_abbr", "maranhão"),
        ("region_code", "9"),
        ("municipality_name", " "),
    ],
)
def test_contract_violations_name_the_column(column: str, value: str) -> None:
    row = {
        "municipality_code": "2111300",
        "municipality_name": "São Luís",
        "state_code": "21",
        "state_abbr": "MA",
        "state_name": "Maranhão",
        "region_code": "2",
        "region_abbr": "NE",
        "region_name": "Nordeste",
    }
    row[column] = value
    with pytest.raises(RowError, match=column):
        MUNICIPALITY.convert(row)
