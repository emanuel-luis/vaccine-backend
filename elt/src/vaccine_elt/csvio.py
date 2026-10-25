"""Byte-deterministic CSV snapshots: UTF-8, comma separated, `\\n` line ends, header row, empty
string for null, gzip with a zero mtime so the same rows always give the same sha256."""

from __future__ import annotations

import csv
import gzip
import io
from collections.abc import Iterable, Iterator, Sequence
from pathlib import Path

Row = dict[str, str]


def write_snapshot(path: Path, columns: Sequence[str], rows: Iterable[Row]) -> int:
    """Write `rows` (already sorted by the caller) and return how many were written."""
    buffer = io.StringIO()
    writer = csv.DictWriter(buffer, fieldnames=list(columns), lineterminator="\n")
    writer.writeheader()
    count = 0
    for row in rows:
        writer.writerow(row)
        count += 1
    data = buffer.getvalue().encode("utf-8")
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    if path.suffix == ".gz":
        with (
            tmp.open("wb") as raw,
            gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0, compresslevel=9) as gz,
        ):
            gz.write(data)
    else:
        tmp.write_bytes(data)
    tmp.replace(path)
    return count


def read_snapshot(path: Path) -> tuple[list[str], Iterator[tuple[int, Row]]]:
    """Return the header and an iterator of (line number, row); line 1 is the header."""
    opener = gzip.open if path.suffix == ".gz" else open
    handle = opener(path, "rt", encoding="utf-8", newline="")
    reader = csv.DictReader(handle)
    header = list(reader.fieldnames or [])

    def rows() -> Iterator[tuple[int, Row]]:
        with handle:
            for row in reader:
                yield reader.line_num, {key: value or "" for key, value in row.items() if key}

    return header, rows()
