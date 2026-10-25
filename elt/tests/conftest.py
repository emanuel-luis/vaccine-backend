"""Shared Testcontainers fixtures: a throwaway Postgres with every Flyway migration applied."""

from __future__ import annotations

import re
from collections.abc import Iterator
from pathlib import Path
from typing import Any

import psycopg
import pytest

from vaccine_elt import config

POSTGRES_IMAGE = "postgres:17.11-alpine"  # same image as compose.yaml


def migrations() -> list[Path]:
    files = config.migrations_dir().glob("V*__*.sql")
    return sorted(files, key=lambda p: int(re.match(r"V(\d+)__", p.name).group(1)))  # type: ignore[union-attr]


@pytest.fixture(scope="module")
def dsn() -> Iterator[str]:
    from testcontainers.community.postgres import PostgresContainer

    with PostgresContainer(POSTGRES_IMAGE, driver=None) as container:
        url = container.get_connection_url()
        with psycopg.connect(url, autocommit=True) as conn:
            for script in migrations():
                conn.execute(script.read_text(encoding="utf-8"))  # type: ignore[call-overload]
        yield url


@pytest.fixture
def conn(dsn: str) -> Iterator[psycopg.Connection[Any]]:
    with psycopg.connect(dsn, autocommit=True) as connection:
        yield connection
