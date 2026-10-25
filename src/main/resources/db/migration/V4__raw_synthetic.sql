-- Landing tables for the synthetic, seeded event generator (`vaccine-elt generate`). Every row is
-- synthetic; a reload with the same seed replaces the contents with identical rows.

CREATE TABLE raw.vaccine_lot (
    lot_number        TEXT PRIMARY KEY,
    vaccine_code      TEXT NOT NULL,
    manufacturer_name TEXT NOT NULL,
    manufactured_on   DATE NOT NULL,
    expires_on        DATE NOT NULL,
    run_id            UUID NOT NULL,
    CHECK (expires_on > manufactured_on)
);

-- One dose given to one synthetic person.
CREATE TABLE raw.vaccination_event (
    event_id        UUID PRIMARY KEY,
    person_id       UUID        NOT NULL,
    birth_date      DATE        NOT NULL,
    sex             CHAR(1)     NOT NULL CHECK (sex IN ('F', 'M')),
    cnes_code       CHAR(7)     NOT NULL,
    vaccine_code    TEXT        NOT NULL,
    dose_label      TEXT        NOT NULL,
    lot_number      TEXT        NOT NULL,
    administered_at TIMESTAMPTZ NOT NULL,
    run_id          UUID        NOT NULL,
    UNIQUE (person_id, vaccine_code, dose_label)
);
CREATE INDEX ix_vaccination_event_administered_at ON raw.vaccination_event (administered_at);

-- One lot shipment received by one unit on one date.
CREATE TABLE raw.vaccine_receipt (
    receipt_id     UUID PRIMARY KEY,
    cnes_code      CHAR(7) NOT NULL,
    lot_number     TEXT    NOT NULL,
    received_on    DATE    NOT NULL,
    doses_received INTEGER NOT NULL CHECK (doses_received > 0),
    run_id         UUID    NOT NULL
);
CREATE INDEX ix_vaccine_receipt_received_on ON raw.vaccine_receipt (received_on);
