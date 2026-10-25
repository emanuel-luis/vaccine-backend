-- Speed layer (docs/WEB_PLATFORM_PLAN.md, section 3.1). Live doses posted to the API land in
-- raw.live_dose and update the incremental aggregate live.dose_daily in the same transaction.
-- Every live row carries the generation that was open when it was inserted; dbt closes a
-- generation (ops.close_live_generation) before it absorbs the rows of the closed generations, and
-- serving adds only live.dose_daily rows above the generation the marts absorbed.
--
-- Generation protocol: an ingest transaction takes ROW EXCLUSIVE on raw.live_dose before it reads
-- the open generation; the close takes SHARE on raw.live_dose, so it waits for every in-flight
-- ingest and blocks new ones until it commits. Once a close of W commits, every row with
-- generation <= W is committed and no later row can get one.

CREATE SCHEMA IF NOT EXISTS live;

-- The open generation: a single row.
CREATE TABLE ops.live_generation (
    id         SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    generation BIGINT   NOT NULL CHECK (generation >= 1)
);
INSERT INTO ops.live_generation (id, generation) VALUES (1, 1);

-- One row per closed generation; closed_by is the dbt invocation id (or a test label).
CREATE TABLE ops.live_generation_close (
    generation BIGINT PRIMARY KEY,
    closed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_by  TEXT        NOT NULL
);

-- Landing: one accepted live dose (synthetic, posted by the simulator). Append-only.
CREATE TABLE raw.live_dose (
    event_id        UUID PRIMARY KEY,
    generation      BIGINT      NOT NULL,
    client_id       TEXT        NOT NULL,
    idempotency_key TEXT        NOT NULL,
    request_sha256  CHAR(64)    NOT NULL,
    person_id       UUID        NOT NULL,
    birth_date      DATE        NOT NULL,
    sex             CHAR(1)     NOT NULL CHECK (sex IN ('F', 'M')),
    cnes_code       CHAR(7)     NOT NULL,
    vaccine_code    TEXT        NOT NULL,
    dose_label      TEXT        NOT NULL,
    lot_number      TEXT,
    administered_at TIMESTAMPTZ NOT NULL,
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_live_dose_idempotency UNIQUE (client_id, idempotency_key),
    CONSTRAINT ux_live_dose_person_dose UNIQUE (person_id, vaccine_code, dose_label)
);
CREATE INDEX ix_live_dose_generation ON raw.live_dose (generation);

-- Incremental aggregate, same grain as marts.mart_doses_daily plus the generation.
-- administered_on is the local calendar date (America/Sao_Paulo) of administered_at, the same
-- rule as stg_synthetic__vaccination_events; state and municipality come from the CNES unit
-- version valid on that date.
CREATE TABLE live.dose_daily (
    generation        BIGINT      NOT NULL,
    administered_on   DATE        NOT NULL,
    cnes_code         CHAR(7)     NOT NULL,
    vaccine_code      TEXT        NOT NULL,
    dose_label        TEXT        NOT NULL,
    age_band_years    SMALLINT    NOT NULL CHECK (age_band_years BETWEEN 0 AND 6),
    municipality_code INTEGER     NOT NULL,
    state_abbr        CHAR(2)     NOT NULL,
    doses             BIGINT      NOT NULL CHECK (doses > 0),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (generation, administered_on, cnes_code, vaccine_code, dose_label, age_band_years)
);
CREATE INDEX ix_dose_daily_state_day ON live.dose_daily (state_abbr, administered_on);
CREATE INDEX ix_dose_daily_municipality_day ON live.dose_daily (municipality_code, administered_on);

-- One row per refused POST /api/v1/doses (4xx). The request body is never stored.
CREATE TABLE ops.ingest_reject (
    reject_id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    client_id       TEXT        NOT NULL,
    idempotency_key TEXT,
    http_status     SMALLINT    NOT NULL CHECK (http_status BETWEEN 400 AND 499),
    reason          TEXT        NOT NULL,
    detail          TEXT
);
CREATE INDEX ix_ingest_reject_received_at ON ops.ingest_reject (received_at);

-- Closes the open generation and returns it (W). Called by the dbt on-run-start hook.
CREATE FUNCTION ops.close_live_generation(p_closed_by TEXT) RETURNS BIGINT
LANGUAGE plpgsql AS $$
DECLARE
    closed BIGINT;
BEGIN
    LOCK TABLE raw.live_dose IN SHARE MODE;
    UPDATE ops.live_generation SET generation = generation + 1 WHERE id = 1
        RETURNING generation - 1 INTO closed;
    INSERT INTO ops.live_generation_close (generation, closed_by) VALUES (closed, p_closed_by);
    RETURN closed;
END;
$$;
