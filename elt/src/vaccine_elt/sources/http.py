"""Small stdlib HTTP helper for the `fetch` command (never used by loads or tests in CI)."""

from __future__ import annotations

import gzip
import shutil
import urllib.request
from dataclasses import dataclass
from email.utils import parsedate_to_datetime
from pathlib import Path

USER_AGENT = "vaccine-elt/0.2 (+https://github.com/emanuel-luis/vaccine-warehouse)"
TIMEOUT_SECONDS = 600


@dataclass(frozen=True)
class Download:
    path: Path
    last_modified: str | None


def download(url: str, destination: Path) -> Download:
    """Stream `url` to `destination` (atomic rename) and return its Last-Modified as ISO UTC."""
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    destination.parent.mkdir(parents=True, exist_ok=True)
    tmp = destination.with_name(destination.name + ".part")
    with (
        urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response,
        tmp.open("wb") as out,
    ):
        # Some endpoints (IBGE) gzip the body even when the client did not ask for it.
        body = (
            gzip.GzipFile(fileobj=response)
            if response.headers.get("Content-Encoding") == "gzip"
            else response
        )
        shutil.copyfileobj(body, out, length=1 << 20)
        header = response.headers.get("Last-Modified")
    tmp.replace(destination)
    last_modified = None
    if header:
        last_modified = parsedate_to_datetime(header).strftime("%Y-%m-%dT%H:%M:%SZ")
    return Download(destination, last_modified)
