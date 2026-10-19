CREATE FUNCTION forbid_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP;
END
$$;

-- A run compares one ledger file with one bank statement file. The tolerances are copied into the run
-- when it is created, so a run's result can always be explained by its own settings.
CREATE TABLE runs (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    label                  TEXT        NOT NULL CHECK (length(label) BETWEEN 1 AND 100),
    status                 TEXT        NOT NULL CHECK (status IN ('RECEIVING', 'MATCHING', 'COMPLETED')),
    amount_tolerance_cents BIGINT      NOT NULL CHECK (amount_tolerance_cents BETWEEN 0 AND 100000),
    date_window_days       INT         NOT NULL CHECK (date_window_days BETWEEN 0 AND 31),
    created_by             TEXT        NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at           TIMESTAMPTZ,
    CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL))
);

CREATE TABLE imports (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id          BIGINT      NOT NULL REFERENCES runs (id),
    side            TEXT        NOT NULL CHECK (side IN ('LEDGER', 'STATEMENT')),
    sha256          TEXT        NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    rows_total      INT         NOT NULL CHECK (rows_total >= 0),
    rows_accepted   INT         NOT NULL CHECK (rows_accepted >= 0),
    rows_rejected   INT         NOT NULL CHECK (rows_rejected >= 0),
    rejections      JSONB       NOT NULL,
    rejections_truncated BOOLEAN NOT NULL,
    ignored_columns JSONB       NOT NULL,
    imported_by     TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (run_id, side),
    CHECK (rows_accepted + rows_rejected = rows_total)
);

CREATE TABLE ledger_records (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id               BIGINT NOT NULL REFERENCES runs (id),
    row_number           INT    NOT NULL CHECK (row_number > 0),
    external_id          TEXT   NOT NULL,
    account_id           TEXT   NOT NULL,
    booking_date         DATE   NOT NULL,
    amount_cents         BIGINT NOT NULL CHECK (amount_cents <> 0),
    currency             TEXT   NOT NULL CHECK (currency = 'USD'),
    reference            TEXT   NOT NULL,
    normalized_reference TEXT   NOT NULL,
    description          TEXT   NOT NULL,
    UNIQUE (run_id, external_id),
    -- Lets matches and breaks reference (id, run, account) together, so the database itself refuses
    -- to pair records from different runs or different accounts.
    UNIQUE (id, run_id, account_id)
);

CREATE INDEX ledger_records_reference_idx ON ledger_records (run_id, account_id, reference);
CREATE INDEX ledger_records_normalized_idx ON ledger_records (run_id, account_id, normalized_reference);

CREATE TABLE statement_lines (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id               BIGINT      NOT NULL REFERENCES runs (id),
    row_number           INT         NOT NULL CHECK (row_number > 0),
    account_id           TEXT        NOT NULL,
    value_date           DATE        NOT NULL,
    amount_cents         BIGINT      NOT NULL CHECK (amount_cents <> 0),
    currency             TEXT        NOT NULL CHECK (currency = 'USD'),
    reference            TEXT        NOT NULL,
    normalized_reference TEXT        NOT NULL,
    description          TEXT        NOT NULL,
    bank_transaction_id  TEXT,
    ingested_by          TEXT        NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- The row number is the line's identity: importing the same line twice is a no-op.
    UNIQUE (run_id, row_number),
    UNIQUE (id, run_id, account_id)
);

CREATE INDEX statement_lines_account_idx ON statement_lines (run_id, account_id, row_number);

CREATE TRIGGER imports_append_only BEFORE UPDATE OR DELETE ON imports
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER imports_no_truncate BEFORE TRUNCATE ON imports
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER ledger_records_append_only BEFORE UPDATE OR DELETE ON ledger_records
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER ledger_records_no_truncate BEFORE TRUNCATE ON ledger_records
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER statement_lines_append_only BEFORE UPDATE OR DELETE ON statement_lines
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER statement_lines_no_truncate BEFORE TRUNCATE ON statement_lines
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();
