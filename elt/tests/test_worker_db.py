"""Job queue, worker recovery, dbt results and loaded sources against a real Postgres
(needs Docker)."""

from __future__ import annotations

import json
import uuid
from pathlib import Path
from typing import Any

import psycopg
import pytest
from test_load_db import sources  # noqa: F401 - fixture

from vaccine_elt.dbt_results import record_dbt_results
from vaccine_elt.load import load_reference
from vaccine_elt.worker import Worker

pytestmark = pytest.mark.db

RUN_RESULTS = Path(__file__).parent / "fixtures" / "run_results.json"
INVOCATION_ID = "5f1c2a9e-0d4b-4c57-9a11-3b7e6d2f8c10"


@pytest.fixture(autouse=True)
def clean(conn: psycopg.Connection[Any]) -> None:
    conn.execute("DELETE FROM ops.job")
    conn.execute("DELETE FROM ops.dbt_invocation")
    conn.execute("DELETE FROM ops.live_generation_close WHERE closed_by = %s", (INVOCATION_ID,))


def queue(conn: psycopg.Connection[Any], kind: str = "reconcile") -> uuid.UUID:
    job_id = uuid.uuid4()
    conn.execute(
        "INSERT INTO ops.job (job_id, kind, status, requested_by)"
        " VALUES (%s, %s, 'queued', 'test')",
        (job_id, kind),
    )
    return job_id


def job_row(conn: psycopg.Connection[Any], job_id: uuid.UUID) -> tuple[Any, ...]:
    row = conn.execute(
        "SELECT status, error, worker_id, started_at IS NOT NULL FROM ops.job WHERE job_id = %s",
        (job_id,),
    ).fetchone()
    assert row is not None
    return row


def test_only_one_job_can_be_active(conn: psycopg.Connection[Any]) -> None:
    queue(conn)
    with pytest.raises(psycopg.errors.UniqueViolation):
        queue(conn, "dbt-build")


def test_claim_takes_the_queued_job_once(conn: psycopg.Connection[Any]) -> None:
    job_id = queue(conn)
    worker = Worker(Path("."), worker_id="w1")

    assert worker.claim(conn) == (job_id, "reconcile")
    assert worker.claim(conn) is None  # running, not queued
    assert job_row(conn, job_id) == ("running", None, "w1", True)


def test_claim_skips_a_job_locked_by_another_worker(
    dsn: str, conn: psycopg.Connection[Any]
) -> None:
    job_id = queue(conn)
    with psycopg.connect(dsn) as other:  # holds the row lock in an open transaction
        other.execute("SELECT 1 FROM ops.job WHERE job_id = %s FOR UPDATE", (job_id,))
        assert Worker(Path("."), worker_id="w2").claim(conn) is None
    assert Worker(Path("."), worker_id="w2").claim(conn) == (job_id, "reconcile")


def test_restart_marks_the_jobs_of_the_same_worker_failed(conn: psycopg.Connection[Any]) -> None:
    job_id = queue(conn)
    Worker(Path("."), worker_id="w").claim(conn)

    Worker(Path("."), worker_id="w").recover(conn)

    assert job_row(conn, job_id)[:2] == ("failed", "worker restarted")
    queue(conn)  # the single-active slot is free again


def test_recover_leaves_a_live_worker_job_and_fails_a_stale_one(
    conn: psycopg.Connection[Any],
) -> None:
    job_id = queue(conn)
    Worker(Path("."), worker_id="other").claim(conn)

    Worker(Path("."), worker_id="mine").recover(conn)
    assert job_row(conn, job_id)[0] == "running"  # fresh heartbeat: still owned

    conn.execute(
        "UPDATE ops.job SET heartbeat_at = now() - interval '1 minute' WHERE job_id = %s",
        (job_id,),
    )
    Worker(Path("."), worker_id="mine").recover(conn)
    assert job_row(conn, job_id)[:2] == ("failed", "worker lost")


def test_execute_cannot_overwrite_a_job_that_was_recovered(
    conn: psycopg.Connection[Any], dsn: str
) -> None:
    job_id = queue(conn)
    owner = Worker(Path("."), worker_id="owner")
    owner.claim(conn)
    conn.execute("UPDATE ops.job SET heartbeat_at = now() - interval '1 minute'")
    Worker(Path("."), worker_id="other").recover(conn)

    with psycopg.connect(dsn, autocommit=True) as log_conn:
        owner.execute(conn, log_conn, job_id, "unknown-kind")

    assert job_row(conn, job_id)[:2] == ("failed", "worker lost")


def test_record_dbt_results_is_idempotent_and_links_the_generation(
    conn: psycopg.Connection[Any],
) -> None:
    conn.execute(
        "INSERT INTO ops.live_generation_close (generation, closed_by)"
        " VALUES ((SELECT coalesce(max(generation), 0) + 1000 FROM ops.live_generation_close), %s)",
        (INVOCATION_ID,),
    )
    job_id = queue(conn, "dbt-build")

    record_dbt_results(conn, RUN_RESULTS, job_id)
    record_dbt_results(conn, RUN_RESULTS, job_id)

    invocation = conn.execute(
        "SELECT command, success, dbt_version, job_id, batch_generation >= 1000"
        " FROM ops.dbt_invocation WHERE invocation_id = %s",
        (INVOCATION_ID,),
    ).fetchall()
    assert invocation == [("dbt build", False, "1.12.0", job_id, True)]
    nodes = conn.execute(
        "SELECT status, count(*) FROM ops.dbt_node_result WHERE invocation_id = %s"
        " GROUP BY status ORDER BY status",
        (INVOCATION_ID,),
    ).fetchall()
    assert nodes == [("fail", 1), ("pass", 1), ("success", 2)]


def test_batch_generation_is_null_when_nothing_was_closed(
    conn: psycopg.Connection[Any],
) -> None:
    record_dbt_results(conn, RUN_RESULTS)
    row = conn.execute(
        "SELECT batch_generation, job_id FROM ops.dbt_invocation WHERE invocation_id = %s",
        (INVOCATION_ID,),
    ).fetchone()
    assert row == (None, None)


def test_load_reference_records_every_manifest_entry(
    conn: psycopg.Connection[Any],
    sources: Path,  # noqa: F811
) -> None:
    runs: list[uuid.UUID] = []
    load_reference(conn, sources, on_start=runs.append)

    rows = conn.execute(
        "SELECT source_id, table_name, license_status, verified"
        " FROM ops.load_run_source WHERE run_id = %s ORDER BY source_id",
        (runs[0],),
    ).fetchall()
    manifest = json.loads((sources / "manifest.json").read_text(encoding="utf-8"))
    assert [r[0] for r in rows] == sorted(s["id"] for s in manifest["sources"])
    assert all(r[2] == "to_be_confirmed" and r[3] for r in rows)
    assert {r[0]: r[1] for r in rows}["census"] == "raw.ibge_census_population"
    assert {r[0]: r[1] for r in rows}["ibge_malha_test"] is None
