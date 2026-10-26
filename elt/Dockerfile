# syntax=docker/dockerfile:1
# Build context is the repository root (compose: `context: .`, `dockerfile: elt/Dockerfile`).
# The image keeps the repository layout because config.REPO_ROOT is three levels above the package:
# /app/elt (code), /app/data/sources (snapshots), /app/dbt (project, for the PR6 worker).

FROM ghcr.io/astral-sh/uv:0.13.5 AS uv

FROM python:3.12.14-slim-bookworm
COPY --from=uv /uv /usr/local/bin/uv
RUN groupadd --system app && useradd --system --gid app --create-home app
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy UV_PYTHON_DOWNLOADS=never \
    PATH=/app/elt/.venv/bin:$PATH
WORKDIR /app/elt

# Dependencies first (cached until the lockfile changes). The dbt group stays: the worker needs it.
COPY elt/pyproject.toml elt/uv.lock elt/.python-version ./
RUN --mount=type=cache,target=/root/.cache/uv \
    uv sync --locked --no-dev --no-install-project

COPY elt/src src
RUN --mount=type=cache,target=/root/.cache/uv \
    uv sync --locked --no-dev

COPY data/sources /app/data/sources
COPY --chown=app:app dbt /app/dbt

USER app
ENTRYPOINT ["vaccine-elt"]
