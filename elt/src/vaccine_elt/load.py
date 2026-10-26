"""`load-reference`: verify the vendored snapshots against the manifest, validate every row
against its contract, then replace each `raw` table in one transaction (TRUNCATE + COPY).

Idempotent: the same snapshots always produce the same table contents (only `run_id` differs)."""

from __future__ import annotations

import json
import logging
import uuid
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import psycopg
from psycopg import sql

from vaccine_elt import csvio, runlog
from vaccine_elt.contracts import (
    CENSUS_POPULATION,
    CNES_UNIT_SNAPSHOT,
    MUNICIPALITY,
    REFERENCE_CONTRACTS,
    Contract,
    RowError,
    Value,
)
from vaccine_elt.manifest import ManifestError, SourceEntry, read_manifest, verify

log = logging.getLogger(__name__)

Check = Callable[[dict[str, Value]], str | None]


@dataclass
class ParsedSource:
    entry: SourceEntry
    rows: list[tuple[Value, ...]] = field(default_factory=list)
    rejects: list[runlog.Reject] = field(default_factory=list)
    rows_read: int = 0


def parse_source(
    directory: Path,
    entry: SourceEntry,
    contract: Contract,
    seen_keys: set[tuple[Value, ...]],
    check: Check | None = None,
) -> ParsedSource:
    """Convert one snapshot into typed rows; contract, duplicate-key and `check` failures become
    rejects. `seen_keys` is shared by every source that lands in the same table."""
    header, rows = csvio.read_snapshot(directory / entry.path)
    if header != entry.columns or sorted(header) != sorted(contract.column_names):
        raise ManifestError(
            f"{entry.id}: header {header} does not match the manifest and {contract.table}"
        )
    parsed = ParsedSource(entry)
    for line_no, row in rows:
        parsed.rows_read += 1
        try:
            values = contract.convert(row)
            key = tuple(values[name] for name in contract.key)
            if key in seen_keys:
                raise RowError(f"duplicate key {key}")
            if check and (problem := check(values)):
                raise RowError(problem)
        except RowError as exc:
            raw_line = json.dumps(row, ensure_ascii=False, sort_keys=True)
            parsed.rejects.append(runlog.Reject(line_no, str(exc), raw_line))
            continue
        seen_keys.add(key)
        parsed.rows.append(tuple(values[name] for name in contract.column_names))
    return parsed


def _replace_table(
    conn: psycopg.Connection[Any],
    run_id: uuid.UUID,
    contract: Contract,
    sources: list[ParsedSource],
) -> list[runlog.SourceResult]:
    schema, table = contract.table.split(".")
    target = sql.Identifier(schema, table)
    columns = sql.SQL(", ").join(sql.Identifier(c) for c in [*contract.column_names, "run_id"])
    results = []
    with conn.transaction():
        conn.execute(sql.SQL("TRUNCATE {}").format(target))
        with conn.cursor() as cur:
            for source in sources:
                copy_sql = sql.SQL("COPY {} ({}) FROM STDIN").format(target, columns)
                with cur.copy(copy_sql) as copy:
                    for row in source.rows:
                        copy.write_row((*row, run_id))
                result = runlog.SourceResult(
                    table=contract.table,
                    source=source.entry.id,
                    sha256=source.entry.sha256,
                    rows_read=source.rows_read,
                    rows_loaded=len(source.rows),
                    rows_rejected=len(source.rejects),
                )
                runlog.record_rejects(conn, run_id, contract.table, source.entry.id, source.rejects)
                runlog.record_source(conn, run_id, result)
                results.append(result)
    return results


def load_reference(
    conn: psycopg.Connection[Any],
    directory: Path,
    on_start: Callable[[uuid.UUID], None] | None = None,
) -> list[runlog.SourceResult]:
    """`on_start` receives the run id as soon as the run row exists (the worker links its job)."""
    manifest = read_manifest(directory)
    params = {"sources": {entry.id: entry.sha256 for entry in manifest.sources}}
    run_id = runlog.start_run(conn, "load-reference", params)
    if on_start:
        on_start(run_id)
    log.info("run %s: load-reference from %s", run_id, directory)
    try:
        verify(directory, manifest)
        by_table = {
            c.table: [e for e in manifest.sources if e.table == c.table]
            for c in REFERENCE_CONTRACTS
        }
        missing = [table for table, entries in by_table.items() if not entries]
        if missing:
            raise ManifestError(f"no source file for {', '.join(missing)}")

        municipality_sources = [
            parse_source(directory, e, MUNICIPALITY, set()) for e in by_table[MUNICIPALITY.table]
        ]
        codes7 = {row[0] for source in municipality_sources for row in source.rows}

        def known_municipality(values: dict[str, Value]) -> str | None:
            code = values["municipality_code"]
            return None if code in codes7 else f"unknown IBGE municipality {code}"

        census_keys: set[tuple[Value, ...]] = set()
        census_sources = [
            parse_source(directory, e, CENSUS_POPULATION, census_keys, known_municipality)
            for e in by_table[CENSUS_POPULATION.table]
        ]
        # CNES rows land even when their municipality code has no IBGE match: dbt maps them to
        # the Unknown municipality (docs/data-model.md). Rejecting them here would drop the unit
        # or fake an "inactive" SCD2 version.
        cnes_keys: set[tuple[Value, ...]] = set()
        cnes_sources = [
            parse_source(directory, e, CNES_UNIT_SNAPSHOT, cnes_keys)
            for e in by_table[CNES_UNIT_SNAPSHOT.table]
        ]

        results = []
        for contract, sources in (
            (MUNICIPALITY, municipality_sources),
            (CENSUS_POPULATION, census_sources),
            (CNES_UNIT_SNAPSHOT, cnes_sources),
        ):
            results.extend(_replace_table(conn, run_id, contract, sources))
        runlog.record_run_sources(conn, run_id, manifest.sources)
    except BaseException as exc:
        runlog.finish_run(conn, run_id, error=f"{type(exc).__name__}: {exc}")
        raise
    runlog.finish_run(conn, run_id)
    return results
