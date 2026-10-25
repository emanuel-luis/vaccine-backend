"""reconcile against a real Postgres with the Flyway migrations applied (needs Docker).

The marts are dbt's, so the two the checks read are created by hand with the columns they need.
"""

from __future__ import annotations

from collections.abc import Iterator
from typing import Any

import psycopg
import pytest
from psycopg.conninfo import conninfo_to_dict

from vaccine_elt.cli import main
from vaccine_elt.reconcile import CheckResult, run_reconcile

pytestmark = pytest.mark.db

DAY = "2026-10-20"
AT = f"{DAY} 15:00+00"  # 12:00 in Brasilia, same local date
UNIT = "0000108"


@pytest.fixture
def db(conn: psycopg.Connection[Any]) -> Iterator[psycopg.Connection[Any]]:
    conn.execute("CREATE SCHEMA IF NOT EXISTS marts")
    conn.execute(
        "CREATE TABLE IF NOT EXISTS marts.dim_vaccine (vaccine_code text, dose_label text)"
    )
    conn.execute(
        "CREATE TABLE IF NOT EXISTS marts.mart_doses_daily (date_day date, cnes_code text,"
        " vaccine_code text, dose_label text, age_band_years integer, doses bigint,"
        " batch_generation bigint)"
    )
    conn.execute(
        "TRUNCATE marts.dim_vaccine, marts.mart_doses_daily, raw.live_dose, live.dose_daily,"
        " raw.vaccination_event, ops.load_run CASCADE"
    )
    conn.execute("INSERT INTO marts.dim_vaccine VALUES ('BCG', 'DU')")
    yield conn


def live_dose(conn: psycopg.Connection[Any], generation: int) -> None:
    conn.execute(
        "INSERT INTO raw.live_dose (event_id, generation, client_id, idempotency_key,"
        " request_sha256, person_id, birth_date, sex, cnes_code, vaccine_code, dose_label,"
        " administered_at) VALUES (gen_random_uuid(), %s, 'test', gen_random_uuid()::text,"
        " repeat('a', 64), gen_random_uuid(), %s, 'F', %s, 'BCG', 'DU', %s)",
        (generation, DAY, UNIT, AT),
    )


def daily(conn: psycopg.Connection[Any], generation: int, doses: int) -> None:
    conn.execute(
        "INSERT INTO live.dose_daily (generation, administered_on, cnes_code, vaccine_code,"
        " dose_label, age_band_years, municipality_code, state_abbr, doses)"
        " VALUES (%s, %s, %s, 'BCG', 'DU', 0, 2111300, 'MA', %s)",
        (generation, DAY, UNIT, doses),
    )


def seed_consistent(conn: psycopg.Connection[Any]) -> None:
    """Two generator events and one live dose per generation; the mart absorbed generation 1."""
    for _ in range(2):
        conn.execute(
            "INSERT INTO raw.vaccination_event (event_id, person_id, birth_date, sex, cnes_code,"
            " vaccine_code, dose_label, lot_number, administered_at, run_id) VALUES"
            " (gen_random_uuid(), gen_random_uuid(), %s, 'M', %s, 'BCG', 'DU', 'L', %s,"
            " gen_random_uuid())",
            (DAY, UNIT, AT),
        )
    live_dose(conn, 1)
    live_dose(conn, 2)
    daily(conn, 1, 1)
    daily(conn, 2, 1)
    conn.execute(
        "INSERT INTO marts.mart_doses_daily VALUES (%s, %s, 'BCG', 'DU', 0, 3, 1)", (DAY, UNIT)
    )


def use_libpq_env(dsn: str, monkeypatch: pytest.MonkeyPatch) -> None:
    """Point the CLI (which connects from PG* variables) at the test container."""
    for key, value in conninfo_to_dict(dsn).items():
        monkeypatch.setenv("PGDATABASE" if key == "dbname" else f"PG{key.upper()}", str(value))


def by_name(results: list[CheckResult]) -> dict[str, CheckResult]:
    return {r.name: r for r in results}


def test_consistent_layers_pass(
    db: psycopg.Connection[Any], dsn: str, monkeypatch: pytest.MonkeyPatch
) -> None:
    seed_consistent(db)

    results = by_name(run_reconcile(db))

    assert all(r.passed for r in results.values())
    assert results["live_aggregate_matches_landing"].expected_total == 2
    assert (
        results["serving_matches_landing"].expected_total,
        results["serving_matches_landing"].actual_total,
    ) == (4, 4)
    assert results["serving_matches_landing"].batch_generation == 1
    status, checks = db.execute(
        "SELECT r.status, (SELECT count(*) FROM ops.reconciliation_check c WHERE c.run_id = r.run_id)"
        " FROM ops.load_run r WHERE command = 'reconcile'"
    ).fetchone()  # type: ignore[misc]
    assert (status, checks) == ("succeeded", 2)
    assert db.execute("SELECT count(*) FROM ops.reconciliation_diff").fetchone() == (0,)

    use_libpq_env(dsn, monkeypatch)
    assert main(["reconcile"]) == 0


def test_live_aggregate_mismatch_fails_only_that_check(db: psycopg.Connection[Any]) -> None:
    seed_consistent(db)
    db.execute("UPDATE live.dose_daily SET doses = 2 WHERE generation = 1")  # absorbed generation

    results = by_name(run_reconcile(db))

    assert not results["live_aggregate_matches_landing"].passed
    assert results["serving_matches_landing"].passed
    (diff,) = results["live_aggregate_matches_landing"].diffs
    assert (diff.expected, diff.actual, diff.key["generation"]) == (1, 2, 1)
    stored = db.execute(
        "SELECT diff_key->>'cnes_code', expected, actual FROM ops.reconciliation_diff"
    ).fetchall()
    assert stored == [(UNIT, 1, 2)]
    status, error = db.execute(
        "SELECT status, error FROM ops.load_run WHERE command = 'reconcile'"
    ).fetchone()  # type: ignore[misc]
    assert status == "failed" and "live_aggregate_matches_landing" in error


def test_serving_mismatch_fails_only_that_check(
    db: psycopg.Connection[Any], dsn: str, monkeypatch: pytest.MonkeyPatch
) -> None:
    seed_consistent(db)
    db.execute("UPDATE marts.mart_doses_daily SET doses = 2")  # the mart lost a dose

    results = by_name(run_reconcile(db))

    assert results["live_aggregate_matches_landing"].passed
    serving = results["serving_matches_landing"]
    assert not serving.passed
    assert (serving.expected_total, serving.actual_total, serving.keys_mismatched) == (4, 3, 1)
    assert serving.diffs[0].key == {
        "administered_on": DAY,
        "vaccine_code": "BCG",
        "dose_label": "DU",
    }

    use_libpq_env(dsn, monkeypatch)
    assert main(["reconcile"]) == 1


def test_unknown_vaccine_is_mapped_like_the_marts(db: psycopg.Connection[Any]) -> None:
    db.execute("INSERT INTO marts.dim_vaccine VALUES ('UNKNOWN', 'UNKNOWN')")
    live_dose(db, 1)
    db.execute("UPDATE raw.live_dose SET vaccine_code = 'XYZ'")
    db.execute(
        "INSERT INTO live.dose_daily (generation, administered_on, cnes_code, vaccine_code,"
        " dose_label, age_band_years, municipality_code, state_abbr, doses)"
        " VALUES (1, %s, %s, 'XYZ', 'DU', 0, 2111300, 'MA', 1)",
        (DAY, UNIT),
    )
    db.execute(
        "INSERT INTO marts.mart_doses_daily VALUES (%s, %s, 'UNKNOWN', 'UNKNOWN', 0, 1, 1)",
        (DAY, UNIT),
    )

    assert all(r.passed for r in run_reconcile(db))
