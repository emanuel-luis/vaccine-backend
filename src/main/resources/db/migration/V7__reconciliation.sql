-- Results of `vaccine-elt reconcile` (docs/WEB_PLATFORM_PLAN.md, section 3.4). One run is one
-- ops.load_run row (command `reconcile`); each check compares two layers over the same snapshot.

CREATE TABLE ops.reconciliation_check (
    run_id           UUID    NOT NULL REFERENCES ops.load_run (run_id) ON DELETE CASCADE,
    check_name       TEXT    NOT NULL,
    batch_generation BIGINT,
    open_generation  BIGINT  NOT NULL,
    keys_compared    BIGINT  NOT NULL CHECK (keys_compared >= 0),
    keys_mismatched  BIGINT  NOT NULL CHECK (keys_mismatched >= 0),
    expected_total   BIGINT  NOT NULL,
    actual_total     BIGINT  NOT NULL,
    passed           BOOLEAN NOT NULL,
    PRIMARY KEY (run_id, check_name)
);

-- The first mismatching keys of a failed check (at most 100 per check).
CREATE TABLE ops.reconciliation_diff (
    run_id     UUID    NOT NULL,
    check_name TEXT    NOT NULL,
    line_no    INTEGER NOT NULL CHECK (line_no BETWEEN 1 AND 100),
    diff_key   JSONB   NOT NULL,
    expected   BIGINT  NOT NULL,
    actual     BIGINT  NOT NULL,
    PRIMARY KEY (run_id, check_name, line_no),
    FOREIGN KEY (run_id, check_name)
        REFERENCES ops.reconciliation_check (run_id, check_name) ON DELETE CASCADE
);
