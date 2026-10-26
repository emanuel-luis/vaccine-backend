"""Worker pieces that need no database: job log cap, level mapping and kind dispatch."""

from __future__ import annotations

import json
import uuid
from pathlib import Path
from typing import Any, cast

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
