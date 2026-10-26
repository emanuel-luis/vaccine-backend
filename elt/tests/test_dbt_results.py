"""Parsing of dbt's run_results.json (no database)."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from vaccine_elt.dbt_results import parse_run_results

FIXTURE = Path(__file__).parent / "fixtures" / "run_results.json"


def test_parse_maps_metadata_and_nodes() -> None:
    invocation = parse_run_results(json.loads(FIXTURE.read_text(encoding="utf-8")))

    assert invocation.invocation_id == "5f1c2a9e-0d4b-4c57-9a11-3b7e6d2f8c10"
    assert invocation.command == "dbt build"
    assert invocation.dbt_version == "1.12.0"
    assert invocation.elapsed_s == 7.5
    assert invocation.generated_at.isoformat() == "2026-10-26T12:00:05.250000+00:00"
    assert [(n.resource_type, n.name, n.status, n.failures) for n in invocation.nodes] == [
        ("model", "stg_cnes_unit", "success", None),
        ("test", "not_null_stg_cnes_unit_cnes_code", "pass", 0),
        ("test", "marts_reconcile_with_raw", "fail", 3),
        ("seed", "pni_child_schedule", "success", None),
    ]
    assert not invocation.success  # one failed test


def test_success_when_no_node_failed() -> None:
    payload = json.loads(FIXTURE.read_text(encoding="utf-8"))
    payload["results"] = [r for r in payload["results"] if r["status"] != "fail"]
    assert parse_run_results(payload).success


def test_malformed_payload_is_a_value_error() -> None:
    with pytest.raises(ValueError, match="malformed"):
        parse_run_results({"metadata": {}})
