-- Run metadata for every ELT command: one row per run, one row per (table, source) loaded, one row
-- per rejected input line. `source` is the manifest id of a vendored file, or `generator` for
-- synthetic data. Timestamps are UTC (timestamptz).

CREATE SCHEMA IF NOT EXISTS ops;

CREATE TABLE ops.load_run (
    run_id          UUID PRIMARY KEY,
    command         TEXT        NOT NULL,
    params          JSONB       NOT NULL DEFAULT '{}'::jsonb,
    seed            BIGINT,
    sample_fraction NUMERIC(20, 12),
    started_at      TIMESTAMPTZ NOT NULL,
    finished_at     TIMESTAMPTZ,
    status          TEXT        NOT NULL CHECK (status IN ('running', 'succeeded', 'failed')),
    error           TEXT,
    CHECK (finished_at IS NULL OR finished_at >= started_at)
);
CREATE INDEX ix_load_run_command_started_at ON ops.load_run (command, started_at DESC);

CREATE TABLE ops.load_run_table (
    run_id        UUID        NOT NULL REFERENCES ops.load_run (run_id) ON DELETE CASCADE,
    table_name    TEXT        NOT NULL,
    source        TEXT        NOT NULL,
    source_sha256 CHAR(64),
    rows_read     BIGINT      NOT NULL CHECK (rows_read >= 0),
    rows_loaded   BIGINT      NOT NULL CHECK (rows_loaded >= 0),
    rows_rejected BIGINT      NOT NULL CHECK (rows_rejected >= 0),
    loaded_at     TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (run_id, table_name, source),
    CHECK (rows_read = rows_loaded + rows_rejected)
);

CREATE TABLE ops.load_reject (
    run_id     UUID   NOT NULL REFERENCES ops.load_run (run_id) ON DELETE CASCADE,
    table_name TEXT   NOT NULL,
    source     TEXT   NOT NULL,
    line_no    BIGINT NOT NULL,
    reason     TEXT   NOT NULL,
    raw_line   TEXT,
    PRIMARY KEY (run_id, table_name, source, line_no)
);
