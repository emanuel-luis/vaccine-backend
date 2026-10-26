"""`vaccine-elt worker`: runs the data-management jobs the API queues in `ops.job`.

One job at a time (a unique index enforces it). The worker claims with FOR UPDATE SKIP LOCKED,
sleeps on `LISTEN job_queued` with a poll fallback, heartbeats every 10 s, writes every log line to
`ops.job_log` and announces it with `pg_notify('job_event', ...)` for the API's SSE stream."""

from __future__ import annotations

import json
import logging
import os
import re
import shutil
import signal
import socket
import subprocess
import tempfile
import threading
import uuid
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path
from types import FrameType
from typing import Any

import psycopg

from vaccine_elt import config, dbt_results
from vaccine_elt.load import load_reference
from vaccine_elt.reconcile import run_reconcile

log = logging.getLogger(__name__)

POLL_SECONDS = 5.0
HEARTBEAT_SECONDS = 10.0
MAX_LOG_LINES = 5000
MAX_MESSAGE_CHARS = 2000
MAX_ERROR_CHARS = 2000
MAX_NOTIFY_BYTES = 7500  # pg_notify payloads are limited to 8000 bytes


class JobFailed(Exception):
    """The job ran to the end and failed; the message becomes `ops.job.error`."""


def alive_file() -> Path:
    return Path(tempfile.gettempdir()) / "worker-alive"


def _level(levelno: int) -> str:
    return "error" if levelno >= logging.ERROR else "warn" if levelno >= logging.WARNING else "info"


def notify(conn: psycopg.Connection[Any], payload: dict[str, Any]) -> None:
    conn.execute("SELECT pg_notify('job_event', %s)", (json.dumps(payload),))


class JobLog:
    """Appends job log lines (own autocommit connection): at most MAX_LOG_LINES, then one
    truncation line; each line is capped at MAX_MESSAGE_CHARS."""

    def __init__(self, conn: psycopg.Connection[Any], job_id: uuid.UUID, kind: str) -> None:
        self.conn = conn
        self.job_id = job_id
        self.kind = kind
        self.seq = 0

    def write(self, level: str, message: str) -> None:
        if self.seq > MAX_LOG_LINES:
            return
        if self.seq == MAX_LOG_LINES:
            level, message = "warn", f"log truncated after {MAX_LOG_LINES} lines"
        self.seq += 1
        message = message.replace("\x00", "")[:MAX_MESSAGE_CHARS]
        self.conn.execute(
            "INSERT INTO ops.job_log (job_id, seq, level, message) VALUES (%s, %s, %s, %s)",
            (self.job_id, self.seq, level, message),
        )
        event = {
            "jobId": str(self.job_id),
            "kind": self.kind,
            "status": "running",
            "seq": self.seq,
            "level": level,
            "message": message,
        }
        if len(json.dumps(event).encode()) > MAX_NOTIFY_BYTES:
            event["message"] = message[:500]  # the full line stays in ops.job_log
        notify(self.conn, event)


class JobLogHandler(logging.Handler):
    """Routes the log records of the thread that runs the job into the job log."""

    def __init__(self, job_log: JobLog) -> None:
        super().__init__()
        self.job_log = job_log
        self.thread_id = threading.get_ident()

    def emit(self, record: logging.LogRecord) -> None:
        if record.thread != self.thread_id:
            return
        try:
            for line in record.getMessage().splitlines():
                if line.strip():
                    self.job_log.write(_level(record.levelno), line)
        except psycopg.Error:
            self.handleError(record)


@dataclass
class JobContext:
    conn: psycopg.Connection[Any]
    job_id: uuid.UUID
    directory: Path
    job_log: JobLog
    worker: Worker

    def link_run(self, run_id: uuid.UUID) -> None:
        self.conn.execute("UPDATE ops.job SET run_id = %s WHERE job_id = %s", (run_id, self.job_id))


def _load_reference(ctx: JobContext) -> None:
    for result in load_reference(ctx.conn, ctx.directory, on_start=ctx.link_run):
        log.info(
            "%s <- %s: read %s, loaded %s, rejected %s",
            result.table,
            result.source,
            result.rows_read,
            result.rows_loaded,
            result.rows_rejected,
        )


def _reconcile(ctx: JobContext) -> None:
    failed = []
    for check in run_reconcile(ctx.conn, on_start=ctx.link_run):
        log.log(
            logging.INFO if check.passed else logging.ERROR,
            "%s: %s, %s/%s keys differ, expected %s, actual %s",
            check.name,
            "passed" if check.passed else "FAILED",
            check.keys_mismatched,
            check.keys_compared,
            check.expected_total,
            check.actual_total,
        )
        if not check.passed:
            failed.append(check.name)
    if failed:
        raise JobFailed(f"reconcile checks failed: {', '.join(failed)}")


def dbt_line_level(line: str) -> str:
    if re.search(r"\b(ERROR|FAIL)\b", line):
        return "error"
    return "warn" if re.search(r"\bWARN(ING)?\b", line) else "info"


def _dbt_build(ctx: JobContext) -> None:
    project = Path(os.environ.get("DBT_PROJECT_DIR") or config.REPO_ROOT / "dbt")
    results = project / "target" / "run_results.json"
    results.unlink(missing_ok=True)  # never record a stale file when dbt dies early
    dbt = shutil.which("dbt") or "dbt"
    command = [dbt, "build", "--no-use-colors", "--project-dir", str(project)]
    command += ["--profiles-dir", str(project)]
    log.info("running: %s", " ".join(command))
    with subprocess.Popen(
        command,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
    ) as proc:
        ctx.worker.proc = proc
        try:
            assert proc.stdout is not None
            for line in proc.stdout:
                ctx.job_log.write(dbt_line_level(line), line.rstrip())
            code = proc.wait()
        finally:
            ctx.worker.proc = None
    if ctx.worker.stop.is_set():
        raise JobFailed("interrupted by SIGTERM")
    if results.exists():
        invocation = dbt_results.record_dbt_results(ctx.conn, results, ctx.job_id)
        ctx.conn.execute(
            "UPDATE ops.job SET dbt_invocation_id = %s WHERE job_id = %s",
            (invocation.invocation_id, ctx.job_id),
        )
        log.info("recorded %d dbt results for %s", len(invocation.nodes), invocation.invocation_id)
    else:
        log.error("dbt wrote no run_results.json")
    if code != 0:
        raise JobFailed(f"dbt build exited with code {code}")
    _reconcile(ctx)


HANDLERS: dict[str, Callable[[JobContext], None]] = {
    "load-reference": _load_reference,
    "dbt-build": _dbt_build,
    "reconcile": _reconcile,
}


def dispatch(kind: str, ctx: JobContext) -> None:
    handler = HANDLERS.get(kind)
    if handler is None:
        raise JobFailed(f"unknown job kind {kind!r}")
    handler(ctx)


@dataclass
class Worker:
    directory: Path
    worker_id: str = field(default_factory=lambda: f"{socket.gethostname()}-{os.getpid()}")
    stop: threading.Event = field(default_factory=threading.Event)
    proc: subprocess.Popen[str] | None = None

    def request_stop(self, *_: int | FrameType | None) -> None:
        """SIGTERM: finish the current line (dbt is terminated, in-process jobs finish)."""
        self.stop.set()
        if self.proc is not None:
            self.proc.terminate()

    def _heartbeat(self) -> None:
        conn: psycopg.Connection[Any] | None = None
        while not self.stop.is_set():
            try:
                alive_file().touch()
                conn = conn or psycopg.connect(autocommit=True)
                conn.execute(
                    "UPDATE ops.job SET heartbeat_at = now()"
                    " WHERE worker_id = %s AND status = 'running'",
                    (self.worker_id,),
                )
            except (psycopg.Error, OSError) as exc:
                log.warning("heartbeat failed: %s", exc)
                conn = None
            self.stop.wait(HEARTBEAT_SECONDS)

    def recover(self, conn: psycopg.Connection[Any]) -> None:
        rows = conn.execute(
            "UPDATE ops.job SET status = 'failed', finished_at = now(), error = 'worker restarted'"
            " WHERE status = 'running' RETURNING job_id, kind"
        ).fetchall()
        for job_id, kind in rows:
            notify(conn, {"jobId": str(job_id), "kind": kind, "status": "failed"})
            log.warning(
                "job %s (%s) was running when the worker restarted: marked failed", job_id, kind
            )

    def claim(self, conn: psycopg.Connection[Any]) -> tuple[uuid.UUID, str] | None:
        row = conn.execute(
            "UPDATE ops.job SET status = 'running', started_at = now(), worker_id = %s,"
            " heartbeat_at = now() WHERE job_id = ("
            " SELECT job_id FROM ops.job WHERE status = 'queued' ORDER BY requested_at"
            " FOR UPDATE SKIP LOCKED LIMIT 1) RETURNING job_id, kind",
            (self.worker_id,),
        ).fetchone()
        return None if row is None else (row[0], row[1])

    def execute(
        self,
        conn: psycopg.Connection[Any],
        log_conn: psycopg.Connection[Any],
        job_id: uuid.UUID,
        kind: str,
    ) -> None:
        notify(conn, {"jobId": str(job_id), "kind": kind, "status": "running"})
        job_log = JobLog(log_conn, job_id, kind)
        handler = JobLogHandler(job_log)
        logging.getLogger().addHandler(handler)
        error: str | None = None
        try:
            log.info("job %s (%s) started", job_id, kind)
            dispatch(kind, JobContext(conn, job_id, self.directory, job_log, self))
        except Exception as exc:  # noqa: BLE001 - any failure marks the job failed
            error = f"{type(exc).__name__}: {exc}" if not isinstance(exc, JobFailed) else str(exc)
            log.error("job failed: %s", error)
        finally:
            logging.getLogger().removeHandler(handler)
        status = "failed" if error else "succeeded"
        conn.execute(
            "UPDATE ops.job SET status = %s, finished_at = now(), error = %s WHERE job_id = %s",
            (status, error[:MAX_ERROR_CHARS] if error else None, job_id),
        )
        notify(conn, {"jobId": str(job_id), "kind": kind, "status": status})

    def run(self) -> int:
        signal.signal(signal.SIGTERM, self.request_stop)
        with (
            psycopg.connect(autocommit=True) as conn,
            psycopg.connect(autocommit=True) as log_conn,
        ):
            self.recover(conn)
            conn.execute("LISTEN job_queued")
            threading.Thread(target=self._heartbeat, daemon=True).start()
            log.info("worker %s ready", self.worker_id)
            try:
                while not self.stop.is_set():
                    if (job := self.claim(conn)) is not None:
                        self.execute(conn, log_conn, *job)
                    else:
                        for _ in conn.notifies(timeout=POLL_SECONDS, stop_after=1):
                            break
            except KeyboardInterrupt:
                pass
            finally:
                self.stop.set()
        return 0
