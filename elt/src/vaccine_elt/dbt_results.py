"""`vaccine-elt record-dbt-results`: copy a dbt `run_results.json` into `ops.dbt_invocation` and
`ops.dbt_node_result`, so the API reads results from the database and never from files.

Idempotent: the invocation id is the key and every insert is ON CONFLICT DO NOTHING. The
`batch_generation` is the live generation that invocation closed (`ops.live_generation_close`), or
NULL when it closed none (for example `dbt test`)."""

from __future__ import annotations

import json
import uuid
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

import psycopg

MAX_MESSAGE_CHARS = 2000
FAILED_STATUSES = frozenset({"error", "fail", "runtime error"})


@dataclass(frozen=True)
class NodeResult:
    unique_id: str
    resource_type: str
    name: str
    status: str
    failures: int | None
    execution_time_s: float | None
    message: str | None


@dataclass(frozen=True)
class Invocation:
    invocation_id: str
    command: str
    generated_at: datetime
    elapsed_s: float | None
    dbt_version: str
    nodes: list[NodeResult]

    @property
    def success(self) -> bool:
        return not any(node.status in FAILED_STATUSES for node in self.nodes)


def _node(item: dict[str, Any]) -> NodeResult:
    unique_id = str(item["unique_id"])
    parts = unique_id.split(".")  # <resource_type>.<package>.<name>[.<hash or version>]
    message = item.get("message")
    failures = item.get("failures")
    return NodeResult(
        unique_id=unique_id,
        resource_type=parts[0],
        name=parts[2] if len(parts) > 2 else parts[-1],
        status=str(item["status"]),
        failures=None if failures is None else int(failures),
        execution_time_s=None
        if item.get("execution_time") is None
        else float(item["execution_time"]),
        message=None if message is None else str(message)[:MAX_MESSAGE_CHARS],
    )


def parse_run_results(payload: dict[str, Any]) -> Invocation:
    try:
        metadata = payload["metadata"]
        elapsed = payload.get("elapsed_time")
        return Invocation(
            invocation_id=str(metadata["invocation_id"]),
            command=f"dbt {payload['args']['which']}",
            generated_at=datetime.fromisoformat(metadata["generated_at"]),
            elapsed_s=None if elapsed is None else float(elapsed),
            dbt_version=str(metadata["dbt_version"]),
            nodes=[_node(item) for item in payload["results"]],
        )
    except (KeyError, TypeError) as exc:
        raise ValueError(f"run_results.json is malformed: {exc!r}") from exc


def record_dbt_results(
    conn: psycopg.Connection[Any], path: Path, job_id: uuid.UUID | None = None
) -> Invocation:
    invocation = parse_run_results(json.loads(path.read_text(encoding="utf-8")))
    with conn.transaction():
        conn.execute(
            "INSERT INTO ops.dbt_invocation (invocation_id, job_id, command, generated_at,"
            " elapsed_s, dbt_version, success, batch_generation)"
            " VALUES (%s, %s, %s, %s, %s, %s, %s,"
            " (SELECT max(generation) FROM ops.live_generation_close WHERE closed_by = %s))"
            " ON CONFLICT (invocation_id) DO NOTHING",
            (
                invocation.invocation_id,
                job_id,
                invocation.command,
                invocation.generated_at,
                invocation.elapsed_s,
                invocation.dbt_version,
                invocation.success,
                invocation.invocation_id,
            ),
        )
        with conn.cursor() as cur:
            cur.executemany(
                "INSERT INTO ops.dbt_node_result (invocation_id, unique_id, resource_type, name,"
                " status, failures, execution_time_s, message) VALUES (%s, %s, %s, %s, %s, %s, %s, %s)"
                " ON CONFLICT (invocation_id, unique_id) DO NOTHING",
                [
                    (
                        invocation.invocation_id,
                        n.unique_id,
                        n.resource_type,
                        n.name,
                        n.status,
                        n.failures,
                        n.execution_time_s,
                        n.message,
                    )
                    for n in invocation.nodes
                ],
            )
    return invocation
