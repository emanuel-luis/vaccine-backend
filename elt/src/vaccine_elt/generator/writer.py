"""`generate`: build the synthetic rows and replace the synthetic raw tables in one transaction."""

from __future__ import annotations

import logging
import uuid
from collections.abc import Iterable, Sequence
from datetime import date
from pathlib import Path
from typing import Any

import psycopg
from psycopg import sql

from vaccine_elt import runlog
from vaccine_elt.generator.core import Generated, generate
from vaccine_elt.generator.inputs import load_reference_data, load_schedule

log = logging.getLogger(__name__)

SOURCE = "generator"
LOT_COLUMNS = ("lot_number", "vaccine_code", "manufacturer_name", "manufactured_on", "expires_on")
EVENT_COLUMNS = (
    "event_id",
    "person_id",
    "birth_date",
    "sex",
    "cnes_code",
    "vaccine_code",
    "dose_label",
    "lot_number",
    "administered_at",
)
RECEIPT_COLUMNS = ("receipt_id", "cnes_code", "lot_number", "received_on", "doses_received")


def _copy(
    cur: psycopg.Cursor[Any],
    table: str,
    columns: Sequence[str],
    rows: Iterable[Sequence[object]],
    run_id: uuid.UUID,
) -> None:
    schema, name = table.split(".")
    statement = sql.SQL("COPY {} ({}) FROM STDIN").format(
        sql.Identifier(schema, name),
        sql.SQL(", ").join(sql.Identifier(c) for c in [*columns, "run_id"]),
    )
    with cur.copy(statement) as copy:
        for row in rows:
            copy.write_row((*row, run_id))


def write(
    conn: psycopg.Connection[Any], run_id: uuid.UUID, data: Generated
) -> list[runlog.SourceResult]:
    tables: list[tuple[str, Sequence[str], list[tuple[object, ...]]]] = [
        (
            "raw.vaccine_lot",
            LOT_COLUMNS,
            [tuple(getattr(x, c) for c in LOT_COLUMNS) for x in data.lots],
        ),
        (
            "raw.vaccination_event",
            EVENT_COLUMNS,
            [tuple(getattr(x, c) for c in EVENT_COLUMNS) for x in data.events],
        ),
        (
            "raw.vaccine_receipt",
            RECEIPT_COLUMNS,
            [tuple(getattr(x, c) for c in RECEIPT_COLUMNS) for x in data.receipts],
        ),
    ]
    results = []
    with conn.transaction(), conn.cursor() as cur:
        cur.execute("TRUNCATE raw.vaccine_receipt, raw.vaccination_event, raw.vaccine_lot")
        for table, columns, rows in tables:
            _copy(cur, table, columns, rows, run_id)
            result = runlog.SourceResult(table, SOURCE, None, len(rows), len(rows), 0)
            runlog.record_source(conn, run_id, result)
            results.append(result)
    return results


def run_generate(
    conn: psycopg.Connection[Any],
    sources_dir: Path,
    schedule_file: Path,
    *,
    rows: int,
    seed: int,
    start: date,
    end: date,
) -> tuple[Generated, list[runlog.SourceResult]]:
    params = {"rows": rows, "seed": seed, "start": start.isoformat(), "end": end.isoformat()}
    run_id = runlog.start_run(conn, "generate", params, seed=seed)
    log.info("run %s: generate %s", run_id, params)
    try:
        reference = load_reference_data(sources_dir, start)
        data = generate(
            reference, load_schedule(schedule_file), rows=rows, seed=seed, start=start, end=end
        )
        results = write(conn, run_id, data)
    except BaseException as exc:
        runlog.finish_run(conn, run_id, error=f"{type(exc).__name__}: {exc}")
        raise
    runlog.finish_run(conn, run_id, sample_fraction=data.sample_fraction)
    return data, results
