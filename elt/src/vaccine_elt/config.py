"""Filesystem locations. Database settings come from the libpq variables (PGHOST, PGPORT,
PGDATABASE, PGUSER, PGPASSWORD), read by psycopg itself."""

from __future__ import annotations

import os
from pathlib import Path

# elt/src/vaccine_elt/config.py -> repository root is three levels above the package directory.
REPO_ROOT = Path(__file__).resolve().parents[3]


def sources_dir() -> Path:
    """Directory holding the vendored source snapshots and their manifest."""
    override = os.environ.get("VACCINE_SOURCES_DIR")
    return Path(override) if override else REPO_ROOT / "data" / "sources"


def migrations_dir() -> Path:
    """Flyway migrations (owned by the API module); tests apply them to a throwaway Postgres."""
    return REPO_ROOT / "src" / "main" / "resources" / "db" / "migration"
