"""Command line: `vaccine-elt fetch | convert-legacy-cnes | load-reference | generate | reconcile`.

Database connection: libpq variables (PGHOST, PGPORT, PGDATABASE, PGUSER, PGPASSWORD)."""

from __future__ import annotations

import argparse
import logging
import sys
import time
from collections.abc import Sequence
from datetime import date
from pathlib import Path

import psycopg

from vaccine_elt import config, fetch
from vaccine_elt.generator.inputs import schedule_path
from vaccine_elt.generator.writer import run_generate
from vaccine_elt.load import load_reference
from vaccine_elt.manifest import Manifest, ManifestError, read_manifest, write_manifest
from vaccine_elt.reconcile import run_reconcile

FETCHERS = {
    "municipalities": fetch.fetch_municipalities,
    "census": fetch.fetch_census,
    "cnes": fetch.fetch_cnes,
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
    commands.add_parser(
        "reconcile",
        help="check live.dose_daily and the serving merge against the landing tables"
        " (exit 1 when a check fails)",
    )
    return parser


def _manifest_or_empty(directory: Path) -> Manifest:
    try:
        return read_manifest(directory)
    except ManifestError:
        if (directory / "manifest.json").exists():
            raise
        return Manifest()


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
                FETCHERS, key=["municipalities", "census", "cnes"].index
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
