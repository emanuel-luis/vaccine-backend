"""Command line: `vaccine-elt fetch | convert-legacy-cnes | load-reference | generate | reconcile | simulate
| record-dbt-results | worker`.

Database connection: libpq variables (PGHOST, PGPORT, PGDATABASE, PGUSER, PGPASSWORD)."""

from __future__ import annotations

import argparse
import logging
import os
import signal
import sys
import time
import uuid
from collections.abc import Sequence
from datetime import date
from pathlib import Path

import psycopg

from vaccine_elt import config, fetch
from vaccine_elt.dbt_results import record_dbt_results
from vaccine_elt.generator.core import LOCAL_TZ
from vaccine_elt.generator.inputs import load_reference_data, load_schedule, schedule_path
from vaccine_elt.generator.writer import run_generate
from vaccine_elt.load import load_reference
from vaccine_elt.manifest import Manifest, ManifestError, read_manifest, write_manifest
from vaccine_elt.reconcile import run_reconcile
from vaccine_elt.simulator import Clock, Drawer, Simulator, TokenCache, urllib_transport
from vaccine_elt.worker import Worker

FETCHERS = {
    "municipalities": fetch.fetch_municipalities,
    "census": fetch.fetch_census,
    "cnes": fetch.fetch_cnes,
    "geo": fetch.fetch_geo,
}


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="vaccine-elt", description=__doc__)
    parser.add_argument(
        "--sources-dir",
        type=Path,
        default=None,
        help="vendored snapshots and manifest (default: data/sources or $VACCINE_SOURCES_DIR)",
    )
    commands = parser.add_subparsers(dest="command", required=True)

    fetch_cmd = commands.add_parser("fetch", help="refresh snapshots from the public endpoints")
    fetch_cmd.add_argument("--only", choices=sorted(FETCHERS), action="append")

    legacy = commands.add_parser(
        "convert-legacy-cnes", help="turn the 2023 unidade_basica.csv into a dated snapshot"
    )
    legacy.add_argument("--csv", type=Path, required=True)
    legacy.add_argument("--snapshot-date", default="2023-06-02")

    commands.add_parser("load-reference", help="verify snapshots and load them into raw")

    gen = commands.add_parser(
        "generate", help="replace the synthetic events, lots and receipts (seeded, deterministic)"
    )
    gen.add_argument("--rows", type=int, default=200_000, help="vaccination events to generate")
    gen.add_argument("--seed", type=int, default=42)
    gen.add_argument("--start", type=date.fromisoformat, default=date(2024, 1, 1))
    gen.add_argument("--end", type=date.fromisoformat, default=date(2025, 12, 31))
    gen.add_argument(
        "--schedule", type=Path, default=None, help="default: dbt/seeds/pni_child_schedule.csv"
    )
    sim = commands.add_parser(
        "simulate", help="post synthetic live doses to the API (Ctrl-C or SIGTERM to stop)"
    )
    sim.add_argument(
        "--rate",
        type=float,
        default=float(os.environ.get("SIMULATOR_RATE_PER_SECOND", "2")),
        help="doses per second, 0 = paused (default: $SIMULATOR_RATE_PER_SECOND or 2)",
    )
    sim.add_argument(
        "--invalid-ratio",
        type=float,
        default=float(os.environ.get("SIMULATOR_INVALID_RATIO", "0.01")),
        help="share of deliberately invalid doses (default: $SIMULATOR_INVALID_RATIO or 0.01)",
    )
    sim.add_argument("--seed", type=int, default=None, help="default: random")
    sim.add_argument("--max-doses", type=int, default=None, help="stop after N doses (demos)")
    commands.add_parser(
        "reconcile",
        help="check live.dose_daily and the serving merge against the landing tables"
        " (exit 1 when a check fails)",
    )
    record = commands.add_parser(
        "record-dbt-results",
        help="copy a dbt run_results.json into ops.dbt_invocation (idempotent)",
    )
    record.add_argument("--run-results", type=Path, required=True)
    record.add_argument("--job-id", type=uuid.UUID, default=None)
    commands.add_parser("worker", help="run the queued data-management jobs (SIGTERM to stop)")
    return parser


def _manifest_or_empty(directory: Path) -> Manifest:
    try:
        return read_manifest(directory)
    except ManifestError:
        if (directory / "manifest.json").exists():
            raise
        return Manifest()


def _simulate(args: argparse.Namespace, directory: Path) -> int:
    secret = os.environ.get("SIMULATOR_CLIENT_SECRET", "")
    api_url = os.environ.get("API_URL", "http://localhost:8080").rstrip("/")
    token_url = os.environ.get(
        "TOKEN_URL", "http://localhost:8180/realms/vaccine/protocol/openid-connect/token"
    )
    if not secret:
        raise ValueError("SIMULATOR_CLIENT_SECRET is not set")
    if args.rate < 0 or not 0 <= args.invalid_ratio <= 1:
        raise ValueError("--rate must be >= 0 and --invalid-ratio within 0..1")
    clock = Clock()
    today = clock.now().astimezone(LOCAL_TZ).date()
    drawer = Drawer(
        load_reference_data(directory, today),
        load_schedule(schedule_path()),
        seed=args.seed,
        invalid_ratio=args.invalid_ratio,
    )
    simulator = Simulator(
        drawer,
        TokenCache(
            urllib_transport,
            clock,
            token_url,
            os.environ.get("SIMULATOR_CLIENT_ID", "vaccine-simulator"),
            secret,
        ),
        urllib_transport,
        clock,
        api_url,
        args.rate,
    )
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))  # PID 1 ignores SIGTERM by default
    try:
        simulator.run(args.max_doses)
    except KeyboardInterrupt:
        pass
    return 0


def main(argv: Sequence[str] | None = None) -> int:
    logging.Formatter.converter = time.gmtime
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)sZ %(levelname)s %(name)s %(message)s",
        stream=sys.stderr,
    )
    args = _parser().parse_args(argv)
    directory: Path = args.sources_dir or config.sources_dir()

    try:
        if args.command == "fetch":
            manifest = _manifest_or_empty(directory)
            for name in args.only or sorted(
                FETCHERS, key=["municipalities", "census", "cnes", "geo"].index
            ):
                entry = FETCHERS[name](directory, manifest)
                write_manifest(directory, manifest)
                print(f"{entry.id}: {entry.rows} rows -> {entry.path}")
        elif args.command == "convert-legacy-cnes":
            manifest = _manifest_or_empty(directory)
            entry = fetch.convert_legacy_cnes(directory, manifest, args.csv, args.snapshot_date)
            write_manifest(directory, manifest)
            print(f"{entry.id}: {entry.rows} rows -> {entry.path}")
        elif args.command == "generate":
            with psycopg.connect(autocommit=True) as conn:
                data, results = run_generate(
                    conn,
                    directory,
                    args.schedule or schedule_path(),
                    rows=args.rows,
                    seed=args.seed,
                    start=args.start,
                    end=args.end,
                )
            for result in results:
                print(f"{result.table}: {result.rows_loaded} rows")
            print(f"persons: {data.persons}, sample_fraction: {data.sample_fraction}")
        elif args.command == "simulate":
            return _simulate(args, directory)
        elif args.command == "worker":
            return Worker(directory).run()
        elif args.command == "record-dbt-results":
            with psycopg.connect(autocommit=True) as conn:
                invocation = record_dbt_results(conn, args.run_results, args.job_id)
            print(f"{invocation.invocation_id}: {len(invocation.nodes)} node results")
        elif args.command == "reconcile":
            with psycopg.connect(autocommit=True) as conn:
                checks = run_reconcile(conn)
            for check in checks:
                print(
                    f"{check.name}: {'passed' if check.passed else 'FAILED'},"
                    f" {check.keys_mismatched}/{check.keys_compared} keys differ,"
                    f" expected {check.expected_total}, actual {check.actual_total}"
                )
            return 0 if all(check.passed for check in checks) else 1
        else:
            with psycopg.connect(autocommit=True) as conn:
                for result in load_reference(conn, directory):
                    print(
                        f"{result.table} <- {result.source}: read {result.rows_read},"
                        f" loaded {result.rows_loaded}, rejected {result.rows_rejected}"
                    )
    except (ManifestError, psycopg.Error, OSError, ValueError) as exc:
        logging.getLogger("vaccine_elt").error("%s failed: %s", args.command, exc)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
