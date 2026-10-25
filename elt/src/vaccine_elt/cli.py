"""Command line: `vaccine-elt fetch | convert-legacy-cnes | load-reference`.

Database connection: libpq variables (PGHOST, PGPORT, PGDATABASE, PGUSER, PGPASSWORD)."""

from __future__ import annotations

import argparse
import logging
import sys
import time
from collections.abc import Sequence
from pathlib import Path

import psycopg

from vaccine_elt import config, fetch
from vaccine_elt.load import load_reference
from vaccine_elt.manifest import Manifest, ManifestError, read_manifest, write_manifest

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
        else:
            with psycopg.connect(autocommit=True) as conn:
                for result in load_reference(conn, directory):
                    print(
                        f"{result.table} <- {result.source}: read {result.rows_read},"
                        f" loaded {result.rows_loaded}, rejected {result.rows_rejected}"
                    )
    except (ManifestError, psycopg.Error, OSError) as exc:
        logging.getLogger("vaccine_elt").error("%s failed: %s", args.command, exc)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
