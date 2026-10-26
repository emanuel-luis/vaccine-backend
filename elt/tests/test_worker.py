"""Worker pieces that need no database: job log cap, level mapping and kind dispatch."""

from __future__ import annotations

import json
import uuid
from pathlib import Path
from typing import Any, Self, cast

import pytest

from vaccine_elt import worker
from vaccine_elt.worker import JobContext, JobFailed, JobLog


class FakeConn:
    def __init__(self) -> None:
        self.inserts: list[tuple[Any, ...]] = []
        self.events: list[dict[str, Any]] = []

    def execute(self, query: str, params: tuple[Any, ...] = ()) -> None:
        if query.startswith("INSERT INTO ops.job_log"):
            self.inserts.append(params)
        else:
            self.events.append(json.loads(params[0]))


def make_log() -> tuple[FakeConn, JobLog]:
    conn = FakeConn()
    return conn, JobLog(cast(Any, conn), uuid.uuid4(), "dbt-build")


def test_log_is_capped_with_one_truncation_line() -> None:
    conn, job_log = make_log()
    for i in range(worker.MAX_LOG_LINES + 50):
        job_log.write("info", f"line {i}")

    assert len(conn.inserts) == worker.MAX_LOG_LINES + 1
    _, seq, level, message = conn.inserts[-1]
    assert seq == worker.MAX_LOG_LINES + 1
    assert level == "warn" and "truncated" in message
    assert conn.inserts[-2][2:] == ("info", f"line {worker.MAX_LOG_LINES - 1}")
    assert conn.events[-1]["seq"] == seq
    assert len(conn.events) == len(conn.inserts)  # one job_event per line


def test_message_is_capped_and_event_fits_a_notification() -> None:
    conn, job_log = make_log()
    job_log.write("error", "ç\x00" * 5000)

    stored = conn.inserts[0][3]
    assert len(stored) == worker.MAX_MESSAGE_CHARS and "\x00" not in stored
    event = conn.events[0]
    assert len(json.dumps(event).encode()) <= 8000
    assert event["level"] == "error" and event["jobId"] == str(conn.inserts[0][0])


def test_dbt_line_levels() -> None:
    assert worker.dbt_line_level("12:00:00  1 of 3 ERROR creating view model x") == "error"
    assert worker.dbt_line_level("12:00:00  [WARNING]: deprecated") == "warn"
    assert worker.dbt_line_level("12:00:00  1 of 3 OK created view") == "info"
    done = "12:00:00  Done. PASS=3 WARN=0 ERROR=0 SKIP=0 NO-OP=0 TOTAL=3"
    assert worker.dbt_line_level(done) == "info"
    assert worker.dbt_line_level(done.replace("WARN=0", "WARN=2")) == "warn"
    assert worker.dbt_line_level(done.replace("ERROR=0", "ERROR=1")) == "error"
    assert worker.dbt_line_level("12:00:00  Completed with 1 error and 0 warnings:") == "error"


def make_ctx() -> JobContext:
    _, job_log = make_log()
    return JobContext(cast(Any, None), uuid.uuid4(), Path("."), job_log, worker.Worker(Path(".")))


def test_dispatch_calls_the_handler_of_the_kind(monkeypatch: pytest.MonkeyPatch) -> None:
    called: list[str] = []

    def recorder(kind: str) -> Any:
        return lambda ctx: called.append(kind)

    for kind in ("load-reference", "dbt-build", "reconcile"):
        monkeypatch.setitem(worker.HANDLERS, kind, recorder(kind))
    ctx = make_ctx()

    for kind in ("reconcile", "load-reference", "dbt-build"):
        worker.dispatch(kind, ctx)

    assert called == ["reconcile", "load-reference", "dbt-build"]


def test_dispatch_rejects_an_unknown_kind() -> None:
    with pytest.raises(JobFailed, match="unknown job kind"):
        worker.dispatch("drop-everything", make_ctx())


class JobConn:
    """Records the statements of a claimed job; `rowcount` is what the guarded UPDATE matched."""

    def __init__(self, rowcount: int = 1) -> None:
        self.rowcount = rowcount
        self.statements: list[tuple[str, tuple[Any, ...]]] = []

    def execute(self, query: str, params: tuple[Any, ...] = ()) -> Any:
        self.statements.append((query, params))
        return self

    def fetchall(self) -> list[tuple[Any, ...]]:
        return []

    def updates(self) -> list[tuple[str, tuple[Any, ...]]]:
        return [s for s in self.statements if s[0].startswith("UPDATE ops.job SET status")]

    def statuses(self) -> list[str]:
        events = (json.loads(p[0]) for q, p in self.statements if "pg_notify" in q)
        return [e["status"] for e in events if "seq" not in e]


def run_job(kind: str, rowcount: int = 1) -> tuple[JobConn, worker.Worker, uuid.UUID]:
    conn, w, job_id = JobConn(rowcount), worker.Worker(Path("."), worker_id="w1"), uuid.uuid4()
    w.execute(cast(Any, conn), cast(Any, FakeConn()), job_id, kind)
    return conn, w, job_id


def test_execute_marks_a_raising_handler_failed(monkeypatch: pytest.MonkeyPatch) -> None:
    def boom(ctx: JobContext) -> None:
        raise ValueError("bad data")

    monkeypatch.setitem(worker.HANDLERS, "reconcile", boom)
    conn, _, job_id = run_job("reconcile")

    ((query, params),) = conn.updates()
    assert "finished_at = now()" in query
    assert "status = 'running' AND worker_id = %s" in query
    assert params == ("failed", "ValueError: bad data", job_id, "w1")
    assert conn.statuses() == ["running", "failed"]


class FakePopen:
    def __init__(self, *_: Any, **__: Any) -> None:
        self.stdout = iter(["12:00:00  1 of 1 ERROR creating model\n"])

    def __enter__(self) -> Self:
        return self

    def __exit__(self, *_: object) -> None:
        return None

    def wait(self) -> int:
        return 2


def test_execute_marks_a_nonzero_dbt_exit_failed(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    monkeypatch.setenv("DBT_PROJECT_DIR", str(tmp_path))
    monkeypatch.setattr(worker.subprocess, "Popen", FakePopen)
    conn, _, job_id = run_job("dbt-build")

    ((_, params),) = conn.updates()
    assert params == ("failed", "dbt build exited with code 2", job_id, "w1")
    assert conn.statuses() == ["running", "failed"]


def test_execute_records_an_interrupt_then_reraises(monkeypatch: pytest.MonkeyPatch) -> None:
    def interrupted(ctx: JobContext) -> None:
        raise KeyboardInterrupt

    monkeypatch.setitem(worker.HANDLERS, "reconcile", interrupted)
    conn = JobConn()
    with pytest.raises(KeyboardInterrupt):
        worker.Worker(Path("."), worker_id="w1").execute(
            cast(Any, conn), cast(Any, FakeConn()), uuid.uuid4(), "reconcile"
        )
    ((query, params),) = conn.updates()
    assert "finished_at = now()" in query
    assert params[:2] == ("failed", "interrupted")
    assert conn.statuses() == ["running", "failed"]


def test_execute_drops_the_result_when_the_job_is_no_longer_ours(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setitem(worker.HANDLERS, "reconcile", lambda ctx: None)
    conn, _, _ = run_job("reconcile", rowcount=0)

    assert conn.statuses() == ["running"]  # nothing announced


def test_recover_only_touches_own_or_stale_jobs() -> None:
    conn = JobConn()
    worker.Worker(Path("."), worker_id="w1").recover(cast(Any, conn))

    ((query, params),) = conn.statements
    assert "WHERE status = 'running' AND (worker_id = %s" in query
    assert "coalesce(heartbeat_at, started_at) < now() - interval '30 seconds'" in query
    assert params == ("w1", "w1")
