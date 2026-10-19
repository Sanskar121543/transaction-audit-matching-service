CREATE TABLE matches (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id            BIGINT      NOT NULL REFERENCES runs (id),
    account_id        TEXT        NOT NULL,
    -- One-to-one: a record can be in at most one match, however many workers try at once.
    ledger_record_id  BIGINT      NOT NULL UNIQUE,
    statement_line_id BIGINT      NOT NULL UNIQUE,
    rule              TEXT        NOT NULL CHECK (rule IN ('EXACT')),
    matched_by        TEXT        NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (ledger_record_id, run_id, account_id) REFERENCES ledger_records (id, run_id, account_id),
    FOREIGN KEY (statement_line_id, run_id, account_id) REFERENCES statement_lines (id, run_id, account_id)
);

CREATE INDEX matches_run_idx ON matches (run_id, id);

CREATE TABLE breaks (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id            BIGINT      NOT NULL REFERENCES runs (id),
    type              TEXT        NOT NULL
        CHECK (type IN ('MISSING_IN_BANK', 'MISSING_IN_LEDGER', 'AMOUNT_MISMATCH', 'DUPLICATE')),
    account_id        TEXT        NOT NULL,
    ledger_record_id  BIGINT      UNIQUE,
    statement_line_id BIGINT      UNIQUE,
    -- What is at stake: the item's amount, or the difference for an amount mismatch. Never negative.
    exposure_cents    BIGINT      NOT NULL CHECK (exposure_cents >= 0),
    -- Aging counts from this date: the ledger's booking date, or the bank's value date for bank-only items.
    item_date         DATE        NOT NULL,
    rule_trace        JSONB       NOT NULL,
    status            TEXT        NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'INVESTIGATING', 'RESOLVED')),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (ledger_record_id, run_id, account_id) REFERENCES ledger_records (id, run_id, account_id),
    FOREIGN KEY (statement_line_id, run_id, account_id) REFERENCES statement_lines (id, run_id, account_id),
    CHECK (CASE type
        WHEN 'MISSING_IN_BANK' THEN ledger_record_id IS NOT NULL AND statement_line_id IS NULL
        WHEN 'MISSING_IN_LEDGER' THEN ledger_record_id IS NULL AND statement_line_id IS NOT NULL
        WHEN 'AMOUNT_MISMATCH' THEN ledger_record_id IS NOT NULL AND statement_line_id IS NOT NULL
        ELSE (ledger_record_id IS NULL) <> (statement_line_id IS NULL)
    END)
);

CREATE INDEX breaks_run_idx ON breaks (run_id, id);
CREATE INDEX breaks_status_idx ON breaks (status, id);

-- Every record ends up either matched or in a break, never both.
CREATE FUNCTION forbid_matched_break() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM matches m
               WHERE m.ledger_record_id = NEW.ledger_record_id OR m.statement_line_id = NEW.statement_line_id) THEN
        RAISE EXCEPTION 'a matched record cannot also be a break';
    END IF;
    RETURN NEW;
END
$$;

CREATE FUNCTION forbid_broken_match() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM breaks b
               WHERE b.ledger_record_id = NEW.ledger_record_id OR b.statement_line_id = NEW.statement_line_id) THEN
        RAISE EXCEPTION 'a record that is already a break cannot be matched';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER breaks_not_matched BEFORE INSERT ON breaks
    FOR EACH ROW EXECUTE FUNCTION forbid_matched_break();
CREATE TRIGGER matches_not_broken BEFORE INSERT ON matches
    FOR EACH ROW EXECUTE FUNCTION forbid_broken_match();

CREATE TRIGGER matches_append_only BEFORE UPDATE OR DELETE ON matches
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER matches_no_truncate BEFORE TRUNCATE ON matches
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER breaks_no_delete BEFORE DELETE ON breaks
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER breaks_no_truncate BEFORE TRUNCATE ON breaks
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();
