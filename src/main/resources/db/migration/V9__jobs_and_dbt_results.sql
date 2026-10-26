-- Data management (docs/WEB_PLATFORM_PLAN.md, section 7). The API queues jobs; the Python worker
-- (`vaccine-elt worker`) claims them with FOR UPDATE SKIP LOCKED, runs the existing ELT and dbt
-- commands and writes the job log. dbt run results and the source manifest are copied into ops so
-- the API never reads files.

CREATE TABLE ops.job (
    job_id            UUID PRIMARY KEY,
    kind              TEXT        NOT NULL CHECK (kind IN ('load-reference', 'dbt-build', 'reconcile')),
    status            TEXT        NOT NULL CHECK (status IN ('queued', 'running', 'succeeded', 'failed')),
    requested_by      TEXT        NOT NULL,
    requested_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at        TIMESTAMPTZ,
    finished_at       TIMESTAMPTZ,
    heartbeat_at      TIMESTAMPTZ,
    worker_id         TEXT,
    run_id            UUID REFERENCES ops.load_run (run_id) ON DELETE SET NULL,
    dbt_invocation_id TEXT,
    error             TEXT,
    CHECK (finished_at IS NULL OR started_at IS NOT NULL),
    CHECK (finished_at IS NULL OR finished_at >= started_at)
);
-- One job at a time is a database rule: a second queued or running row violates this index.
CREATE UNIQUE INDEX ux_job_single_active ON ops.job ((1)) WHERE status IN ('queued', 'running');
CREATE INDEX ix_job_requested_at ON ops.job (requested_at DESC);

CREATE TABLE ops.job_log (
    job_id    UUID        NOT NULL REFERENCES ops.job (job_id) ON DELETE CASCADE,
    seq       INTEGER     NOT NULL CHECK (seq >= 1),
    logged_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    level     TEXT        NOT NULL CHECK (level IN ('info', 'warn', 'error')),
    message   TEXT        NOT NULL CHECK (char_length(message) <= 2000),
    PRIMARY KEY (job_id, seq)
);

-- One row per dbt invocation recorded from run_results.json (host `make dbt-build` or a worker job).
CREATE TABLE ops.dbt_invocation (
    invocation_id    TEXT PRIMARY KEY,
    job_id           UUID REFERENCES ops.job (job_id) ON DELETE SET NULL,
    command          TEXT        NOT NULL,
    generated_at     TIMESTAMPTZ NOT NULL,
    elapsed_s        NUMERIC(12, 3),
    dbt_version      TEXT        NOT NULL,
    success          BOOLEAN     NOT NULL,
    batch_generation BIGINT
);
CREATE INDEX ix_dbt_invocation_generated_at ON ops.dbt_invocation (generated_at DESC);

CREATE TABLE ops.dbt_node_result (
    invocation_id    TEXT    NOT NULL REFERENCES ops.dbt_invocation (invocation_id) ON DELETE CASCADE,
    unique_id        TEXT    NOT NULL,
    resource_type    TEXT    NOT NULL,
    name             TEXT    NOT NULL,
    status           TEXT    NOT NULL,
    failures         BIGINT,
    execution_time_s NUMERIC(12, 3),
    message          TEXT,
    PRIMARY KEY (invocation_id, unique_id)
);

-- The manifest entries a load-reference run verified (snapshots and geometries).
CREATE TABLE ops.load_run_source (
    run_id         UUID        NOT NULL REFERENCES ops.load_run (run_id) ON DELETE CASCADE,
    source_id      TEXT        NOT NULL,
    path           TEXT        NOT NULL,
    table_name     TEXT,
    publisher      TEXT        NOT NULL,
    source_url     TEXT        NOT NULL,
    license_note   TEXT        NOT NULL,
    license_status TEXT        NOT NULL CHECK (license_status IN ('to_be_confirmed', 'confirmed')),
    retrieved_at   TEXT        NOT NULL,
    snapshot_date  TEXT        NOT NULL,
    rows           BIGINT      NOT NULL CHECK (rows >= 0),
    sha256         CHAR(64)    NOT NULL,
    verified       BOOLEAN     NOT NULL,
    PRIMARY KEY (run_id, source_id)
);
