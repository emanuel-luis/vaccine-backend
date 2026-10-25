"""Run metadata in `ops`: every command records a run, per-source row counts and rejects."""

from __future__ import annotations

import json
import uuid
from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from datetime import UTC, datetime
from decimal import Decimal
from typing import Any

import psycopg

MAX_ERROR_CHARS = 2000


@dataclass(frozen=True)
class Reject:
    line_no: int
    reason: str
    raw_line: str


@dataclass(frozen=True)
class SourceResult:
    table: str
    source: str
    sha256: str | None
    rows_read: int
    rows_loaded: int
    rows_rejected: int


def start_run(
    conn: psycopg.Connection[Any],
    command: str,
    params: Mapping[str, object],
    seed: int | None = None,
) -> uuid.UUID:
    run_id = uuid.uuid4()
    conn.execute(
        "INSERT INTO ops.load_run (run_id, command, params, seed, started_at, status)"
        " VALUES (%s, %s, %s::jsonb, %s, %s, 'running')",
        (run_id, command, json.dumps(params, sort_keys=True), seed, datetime.now(UTC)),
    )
    return run_id


def finish_run(
    conn: psycopg.Connection[Any],
    run_id: uuid.UUID,
    *,
    error: str | None = None,
    sample_fraction: Decimal | None = None,
) -> None:
    conn.execute(
        "UPDATE ops.load_run SET finished_at = %s, status = %s, error = %s,"
        " sample_fraction = coalesce(%s, sample_fraction) WHERE run_id = %s",
        (
            datetime.now(UTC),
            "failed" if error else "succeeded",
            error[:MAX_ERROR_CHARS] if error else None,
            sample_fraction,
            run_id,
        ),
    )


def record_source(conn: psycopg.Connection[Any], run_id: uuid.UUID, result: SourceResult) -> None:
    conn.execute(
        "INSERT INTO ops.load_run_table (run_id, table_name, source, source_sha256, rows_read,"
        " rows_loaded, rows_rejected, loaded_at) VALUES (%s, %s, %s, %s, %s, %s, %s, %s)",
        (
            run_id,
            result.table,
            result.source,
            result.sha256,
            result.rows_read,
            result.rows_loaded,
            result.rows_rejected,
            datetime.now(UTC),
        ),
    )


def record_rejects(
    conn: psycopg.Connection[Any],
    run_id: uuid.UUID,
    table: str,
    source: str,
    rejects: Iterable[Reject],
) -> None:
    with conn.cursor() as cur:
        cur.executemany(
            "INSERT INTO ops.load_reject (run_id, table_name, source, line_no, reason, raw_line)"
            " VALUES (%s, %s, %s, %s, %s, %s)",
            [(run_id, table, source, r.line_no, r.reason, r.raw_line) for r in rejects],
        )
