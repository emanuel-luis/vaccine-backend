"""load-reference against a real Postgres with the Flyway migrations applied (needs Docker)."""

from __future__ import annotations

import re
from collections.abc import Iterator
from pathlib import Path
from typing import Any

import psycopg
import pytest

from vaccine_elt import config, csvio
from vaccine_elt.load import load_reference
from vaccine_elt.manifest import Manifest, ManifestError, SourceEntry, sha256_file, write_manifest
from vaccine_elt.sources import cnes, ibge

pytestmark = pytest.mark.db

POSTGRES_IMAGE = "postgres:17.11-alpine"  # same image as compose.yaml


def migrations() -> list[Path]:
    files = config.migrations_dir().glob("V*__*.sql")
    return sorted(files, key=lambda p: int(re.match(r"V(\d+)__", p.name).group(1)))  # type: ignore[union-attr]


@pytest.fixture(scope="module")
def dsn() -> Iterator[str]:
    from testcontainers.community.postgres import PostgresContainer

    with PostgresContainer(POSTGRES_IMAGE, driver=None) as container:
        url = container.get_connection_url()
        with psycopg.connect(url, autocommit=True) as conn:
            for script in migrations():
                conn.execute(script.read_text(encoding="utf-8"))  # type: ignore[call-overload]
        yield url


@pytest.fixture
def conn(dsn: str) -> Iterator[psycopg.Connection[Any]]:
    with psycopg.connect(dsn, autocommit=True) as connection:
        yield connection


def add(
    directory: Path,
    manifest: Manifest,
    source_id: str,
    table: str,
    columns: list[str],
    rows: list[dict[str, str]],
) -> None:
    path = directory / f"{source_id}.csv.gz"
    count = csvio.write_snapshot(path, columns, rows)
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
            snapshot_date="2026-10-25",
            transform="none",
            columns=columns,
            rows=count,
            sha256=sha256_file(path),
        )
    )


def unit(date: str, code: str, municipality6: str, name: str) -> dict[str, str]:
    return {
        "snapshot_date": date,
        "cnes_code": code,
        "municipality_code6": municipality6,
        "unit_name": name,
        "neighborhood": "CENTRO",
        "street": "",
        "unit_type_code": "2",
        "is_active": "true",
        "latitude": "",
        "longitude": "",
    }


@pytest.fixture
def sources(tmp_path: Path) -> Path:
    manifest = Manifest()
    add(
        tmp_path,
        manifest,
        "municipalities",
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
        tmp_path,
        manifest,
        "census",
        "raw.ibge_census_population",
        ibge.CENSUS_COLUMNS,
        [
            {
                "census_year": "2022",
                "municipality_code": "2111300",
                "sex": "F",
                "age_years": "0",
                "population": "7000",
            },
            {
                "census_year": "2022",
                "municipality_code": "9999999",
                "sex": "F",
                "age_years": "0",
                "population": "1",
            },
        ],
    )
    add(
        tmp_path,
        manifest,
        "cnes_2023",
        "raw.cnes_unit_snapshot",
        cnes.COLUMNS,
        [
            unit("2023-06-02", "0000108", "211130", "UBS A"),
        ],
    )
    add(
        tmp_path,
        manifest,
        "cnes_2026",
        "raw.cnes_unit_snapshot",
        cnes.COLUMNS,
        [
            unit("2026-10-24", "0000108", "211130", "UBS A RENAMED"),
            unit("2026-10-24", "0000108", "211130", "UBS A DUPLICATE"),
            unit("2026-10-24", "0000116", "999999", "UBS NOWHERE"),
        ],
    )
    write_manifest(tmp_path, manifest)
    return tmp_path


def table_dump(conn: psycopg.Connection[Any], table: str) -> list[tuple[Any, ...]]:
    query = f"SELECT * FROM {table} ORDER BY 1, 2"
    return [row[:-1] for row in conn.execute(query).fetchall()]  # drop run_id (last column)


def test_load_is_idempotent_and_records_rejects(
    conn: psycopg.Connection[Any], sources: Path
) -> None:
    first = load_reference(conn, sources)
    snapshot = {t: table_dump(conn, t) for t in ("raw.ibge_municipality", "raw.cnes_unit_snapshot")}
    second = load_reference(conn, sources)

    assert [(r.source, r.rows_loaded, r.rows_rejected) for r in first] == [
        ("municipalities", 1, 0),
        ("census", 1, 1),
        ("cnes_2023", 1, 0),
        ("cnes_2026", 1, 2),
    ]
    assert second == first
    assert {t: table_dump(conn, t) for t in snapshot} == snapshot

    reasons = [
        r
        for (r,) in conn.execute(
            "SELECT reason FROM ops.load_reject WHERE run_id = (SELECT run_id FROM ops.load_run"
            " WHERE status = 'succeeded' ORDER BY started_at DESC LIMIT 1) ORDER BY source, line_no"
        )
    ]
    assert any("unknown IBGE municipality 9999999" in r for r in reasons)
    assert any("duplicate key" in r for r in reasons)
    assert any("unknown 6-digit municipality 999999" in r for r in reasons)


def test_tampered_snapshot_fails_the_run_and_keeps_the_tables(
    conn: psycopg.Connection[Any], sources: Path
) -> None:
    load_reference(conn, sources)
    before = table_dump(conn, "raw.cnes_unit_snapshot")
    target = sources / "cnes_2026.csv.gz"
    target.write_bytes(target.read_bytes() + b"x")

    with pytest.raises(ManifestError, match="sha256"):
        load_reference(conn, sources)

    status, error = conn.execute(
        "SELECT status, error FROM ops.load_run ORDER BY started_at DESC LIMIT 1"
    ).fetchone()  # type: ignore[misc]
    assert status == "failed" and "sha256" in error
    assert table_dump(conn, "raw.cnes_unit_snapshot") == before
