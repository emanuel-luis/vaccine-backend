-- Vaccination campaigns (docs/WEB_PLATFORM_PLAN.md, section 3.2). The API is the only writer.
-- `version` is the optimistic lock behind the ETag / If-Match of /api/v1/campaigns.
CREATE SCHEMA IF NOT EXISTS app;

CREATE TABLE app.campaign (
    campaign_id  UUID PRIMARY KEY,
    name         TEXT        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 120),
    vaccine_code TEXT        NOT NULL,
    -- NULL = every dose of the vaccine
    dose_label   TEXT,
    -- same syntax as the region_scope token claim: BR | UF:XX | MUN:1234567
    region_scope TEXT        NOT NULL CHECK (region_scope ~ '^(BR|UF:[A-Z]{2}|MUN:[0-9]{7})$'),
    starts_on    DATE        NOT NULL,
    ends_on      DATE        NOT NULL,
    target_doses INTEGER     NOT NULL CHECK (target_doses > 0),
    created_by   TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    version      INTEGER     NOT NULL DEFAULT 1,
    CONSTRAINT campaign_dates_ordered CHECK (ends_on >= starts_on)
);

CREATE INDEX ix_campaign_region ON app.campaign (region_scope);
CREATE INDEX ix_campaign_period ON app.campaign (starts_on, ends_on);
