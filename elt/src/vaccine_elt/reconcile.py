"""`vaccine-elt reconcile`: compares the layers of the speed/batch design over one snapshot.

Both checks run in one REPEATABLE READ READ ONLY transaction, so the API can keep ingesting while
they run. Results are written afterwards, in a separate transaction, to ops.reconciliation_check
and ops.reconciliation_diff; run metadata goes to ops.load_run (command `reconcile`).

- live_aggregate_matches_landing: live.dose_daily vs raw.live_dose, grouped by generation, local
  date, CNES unit, vaccine, dose and age band.
- serving_matches_landing: the serving merge (marts.mart_doses_daily plus the live.dose_daily rows
  above the mart's batch_generation) vs raw.vaccination_event plus raw.live_dose, grouped by
  local date, vaccine and dose. Vaccine and dose pairs that are not in marts.dim_vaccine are
  mapped to UNKNOWN on the landing side, the rule fact_vaccination applies, so a code outside the
  schedule is not reported as a lost dose.
"""

from __future__ import annotations

import uuid
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any

import psycopg
from psycopg.types.json import Jsonb

from vaccine_elt import runlog

MAX_DIFFS = 100
LOCAL_DATE = "(administered_at at time zone 'America/Sao_Paulo')::date"

# Each check is a CTE chain ending in `joined(<key columns>, expected, actual)`, one row per key
# present on either side. The query for the totals and the one for the first diffs share it.
LIVE_AGGREGATE = f"""
WITH expected AS (
    SELECT generation, {LOCAL_DATE} AS administered_on, cnes_code::text AS cnes_code,
           vaccine_code, dose_label,
           extract(year FROM age({LOCAL_DATE}, birth_date))::int AS age_band_years,
           count(*) AS doses
    FROM raw.live_dose
    GROUP BY 1, 2, 3, 4, 5, 6
), actual AS (
    SELECT generation, administered_on, cnes_code::text AS cnes_code, vaccine_code, dose_label,
           age_band_years::int AS age_band_years, doses
    FROM live.dose_daily
), joined AS (
    SELECT generation, administered_on, cnes_code, vaccine_code, dose_label, age_band_years,
           coalesce(e.doses, 0) AS expected, coalesce(a.doses, 0) AS actual
    FROM expected e
    FULL JOIN actual a USING (generation, administered_on, cnes_code, vaccine_code, dose_label,
                              age_band_years)
)
"""
LIVE_AGGREGATE_KEYS = (
    "generation",
    "administered_on",
    "cnes_code",
    "vaccine_code",
    "dose_label",
    "age_band_years",
)

SERVING = f"""
WITH mart_generation AS (
    SELECT coalesce(max(batch_generation), 0) AS g FROM marts.mart_doses_daily
), schedule AS (
    SELECT vaccine_code, dose_label FROM marts.dim_vaccine
), landing AS (
    SELECT {LOCAL_DATE} AS administered_on, vaccine_code, dose_label FROM raw.vaccination_event
    UNION ALL
    SELECT {LOCAL_DATE}, vaccine_code, dose_label FROM raw.live_dose
), expected AS (
    SELECT l.administered_on, coalesce(s.vaccine_code, 'UNKNOWN') AS vaccine_code,
           coalesce(s.dose_label, 'UNKNOWN') AS dose_label, count(*) AS doses
    FROM landing l
    LEFT JOIN schedule s ON s.vaccine_code = l.vaccine_code AND s.dose_label = l.dose_label
    GROUP BY 1, 2, 3
), merged AS (
    SELECT date_day AS administered_on, vaccine_code, dose_label, doses
    FROM marts.mart_doses_daily
    UNION ALL
    SELECT d.administered_on, d.vaccine_code, d.dose_label, d.doses
    FROM live.dose_daily d
    CROSS JOIN mart_generation w
    WHERE d.generation > w.g
), actual AS (
    SELECT m.administered_on, coalesce(s.vaccine_code, 'UNKNOWN') AS vaccine_code,
           coalesce(s.dose_label, 'UNKNOWN') AS dose_label, sum(m.doses) AS doses
    FROM merged m
    LEFT JOIN schedule s ON s.vaccine_code = m.vaccine_code AND s.dose_label = m.dose_label
    GROUP BY 1, 2, 3
), joined AS (
    SELECT administered_on, vaccine_code, dose_label,
           coalesce(e.doses, 0) AS expected, coalesce(a.doses, 0) AS actual
    FROM expected e
    FULL JOIN actual a USING (administered_on, vaccine_code, dose_label)
)
"""
SERVING_KEYS = ("administered_on", "vaccine_code", "dose_label")


@dataclass(frozen=True)
class Diff:
    key: dict[str, Any]
    expected: int
    actual: int


@dataclass(frozen=True)
class CheckResult:
    name: str
    batch_generation: int | None
    open_generation: int
    keys_compared: int
    keys_mismatched: int
    expected_total: int
    actual_total: int
    diffs: list[Diff] = field(default_factory=list)

    @property
    def passed(self) -> bool:
        return self.keys_mismatched == 0 and self.expected_total == self.actual_total


def _check(
    cur: psycopg.Cursor[Any],
    name: str,
    ctes: str,
    keys: tuple[str, ...],
    batch_generation: int | None,
    open_generation: int,
) -> CheckResult:
    cur.execute(
        ctes + "SELECT count(*), count(*) FILTER (WHERE expected <> actual),"
        " coalesce(sum(expected), 0)::bigint, coalesce(sum(actual), 0)::bigint FROM joined"
    )
    compared, mismatched, expected_total, actual_total = cur.fetchone() or (0, 0, 0, 0)
    key_json = ", ".join(f"'{k}', {k}" for k in keys)
    cur.execute(
        ctes
        + f"SELECT jsonb_build_object({key_json}), expected::bigint, actual::bigint FROM joined"
        f" WHERE expected <> actual ORDER BY {', '.join(keys)} LIMIT {MAX_DIFFS}"
    )
    diffs = [Diff(key, int(e), int(a)) for key, e, a in cur.fetchall()]
    return CheckResult(
        name,
        batch_generation,
        open_generation,
        compared,
        mismatched,
        expected_total,
        actual_total,
        diffs,
    )


def run_checks(conn: psycopg.Connection[Any]) -> list[CheckResult]:
    """Run every check in one REPEATABLE READ READ ONLY transaction (one snapshot)."""
    conn.isolation_level = psycopg.IsolationLevel.REPEATABLE_READ
    conn.read_only = True
    try:
        with conn.transaction(), conn.cursor() as cur:
            # LOCK takes no snapshot, so the snapshot of the first SELECT comes after it: a dbt
            # build cannot swap these marts in between, which would make the swapped-in tables
            # look empty to a REPEATABLE READ snapshot taken before the swap committed.
            cur.execute("LOCK TABLE marts.mart_doses_daily, marts.dim_vaccine IN ACCESS SHARE MODE")
            cur.execute("SELECT generation FROM ops.live_generation WHERE id = 1")
            open_generation = (cur.fetchone() or (1,))[0]
            cur.execute("SELECT max(generation) FROM ops.live_generation_close")
            closed = (cur.fetchone() or (None,))[0]
            cur.execute("SELECT coalesce(max(batch_generation), 0) FROM marts.mart_doses_daily")
            mart_generation = (cur.fetchone() or (0,))[0]
            return [
                _check(
                    cur,
                    "live_aggregate_matches_landing",
                    LIVE_AGGREGATE,
                    LIVE_AGGREGATE_KEYS,
                    closed,
                    open_generation,
                ),
                _check(
                    cur,
                    "serving_matches_landing",
                    SERVING,
                    SERVING_KEYS,
                    mart_generation,
                    open_generation,
                ),
            ]
    finally:
        conn.isolation_level = None
        conn.read_only = None


def record(conn: psycopg.Connection[Any], run_id: uuid.UUID, results: list[CheckResult]) -> None:
    failed = [r.name for r in results if not r.passed]
    with conn.transaction():
        for r in results:
            conn.execute(
                "INSERT INTO ops.reconciliation_check (run_id, check_name, batch_generation,"
                " open_generation, keys_compared, keys_mismatched, expected_total, actual_total,"
                " passed) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)",
                (
                    run_id,
                    r.name,
                    r.batch_generation,
                    r.open_generation,
                    r.keys_compared,
                    r.keys_mismatched,
                    r.expected_total,
                    r.actual_total,
                    r.passed,
                ),
            )
            for line_no, diff in enumerate(r.diffs, start=1):
                conn.execute(
                    "INSERT INTO ops.reconciliation_diff (run_id, check_name, line_no, diff_key,"
                    " expected, actual) VALUES (%s, %s, %s, %s, %s, %s)",
                    (run_id, r.name, line_no, Jsonb(diff.key), diff.expected, diff.actual),
                )
        runlog.finish_run(
            conn, run_id, error=f"checks failed: {', '.join(failed)}" if failed else None
        )


def run_reconcile(
    conn: psycopg.Connection[Any], on_start: Callable[[uuid.UUID], None] | None = None
) -> list[CheckResult]:
    """Run the checks and record them. A failed check marks the run failed; it does not raise.
    `on_start` receives the run id as soon as the run row exists (the worker links its job)."""
    run_id = runlog.start_run(conn, "reconcile", {})
    if on_start:
        on_start(run_id)
    try:
        results = run_checks(conn)
        record(conn, run_id, results)
    except Exception as exc:
        runlog.finish_run(conn, run_id, error=str(exc))
        raise
    return results
