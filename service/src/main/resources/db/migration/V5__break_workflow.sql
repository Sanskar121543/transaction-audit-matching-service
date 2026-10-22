-- People work each break from OPEN through INVESTIGATING to RESOLVED. The database keeps the trail: triggers
-- write every change to a break's workflow, and every note, into break_events in the same transaction, naming
-- who made it. The application names its caller with SET LOCAL matching.actor; a change that names nobody is
-- refused, so the trail stays complete even for changes made by hand in psql. Neither the trail nor the notes
-- can be edited or deleted.

ALTER TABLE breaks
    ADD COLUMN assignee   TEXT CHECK (assignee ~ '^[A-Za-z0-9._@-]{1,64}$'),
    ADD COLUMN resolution TEXT CHECK (resolution IN ('TIMING_DIFFERENCE', 'BANK_FEE', 'BANK_ERROR',
                                                     'LEDGER_CORRECTED', 'WRITTEN_OFF', 'OTHER')),
    -- Raised by the trigger below on every change, so a request based on an older read is refused (409)
    -- instead of overwriting whatever changed since, including changes made outside this service.
    ADD COLUMN version    BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    ADD COLUMN updated_at TIMESTAMPTZ;

-- Someone owns every break past OPEN, and exactly the resolved ones say how they were resolved.
ALTER TABLE breaks ADD CHECK (status = 'OPEN' OR assignee IS NOT NULL);
ALTER TABLE breaks ADD CHECK ((status = 'RESOLVED') = (resolution IS NOT NULL));

CREATE INDEX breaks_assignee_idx ON breaks (assignee, id) WHERE assignee IS NOT NULL;

CREATE TABLE break_notes (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    break_id   BIGINT      NOT NULL REFERENCES breaks (id),
    author     TEXT        NOT NULL,
    body       TEXT        NOT NULL CHECK (length(body) BETWEEN 1 AND 2000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX break_notes_break_idx ON break_notes (break_id, id);

-- One row per change. A status change records the assignee before and after too, since one request can move
-- both; a note records only which note it was.
CREATE TABLE break_events (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    break_id       BIGINT      NOT NULL REFERENCES breaks (id),
    actor          TEXT        NOT NULL,
    action         TEXT        NOT NULL CHECK (action IN ('STATUS_CHANGED', 'ASSIGNED', 'NOTE_ADDED')),
    from_status    TEXT,
    to_status      TEXT,
    from_assignee  TEXT,
    to_assignee    TEXT,
    resolution     TEXT,
    note_id        BIGINT REFERENCES break_notes (id),
    correlation_id TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((action = 'NOTE_ADDED') = (note_id IS NOT NULL))
);

CREATE INDEX break_events_break_idx ON break_events (break_id, id);

CREATE FUNCTION workflow_actor() RETURNS TEXT
LANGUAGE plpgsql AS $$
DECLARE
    -- NULL if never set in this session, '' once a SET LOCAL from an earlier transaction has ended.
    actor TEXT := current_setting('matching.actor', true);
BEGIN
    IF actor IS NULL OR actor = '' THEN
        RAISE EXCEPTION 'name who is making this change first: SET LOCAL matching.actor = ''<client id>''';
    END IF;
    RETURN actor;
END
$$;

CREATE FUNCTION workflow_correlation_id() RETURNS TEXT
LANGUAGE sql AS $$
    SELECT NULLIF(current_setting('matching.correlation_id', true), '')
$$;

CREATE FUNCTION check_break_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.id, NEW.run_id, NEW.type, NEW.account_id, NEW.ledger_record_id, NEW.statement_line_id,
        NEW.exposure_cents, NEW.item_date, NEW.rule_trace, NEW.created_at)
       IS DISTINCT FROM
       (OLD.id, OLD.run_id, OLD.type, OLD.account_id, OLD.ledger_record_id, OLD.statement_line_id,
        OLD.exposure_cents, OLD.item_date, OLD.rule_trace, OLD.created_at) THEN
        RAISE EXCEPTION 'breaks: only status, assignee and resolution can change';
    END IF;
    IF (NEW.status, NEW.assignee, NEW.resolution) IS NOT DISTINCT FROM (OLD.status, OLD.assignee, OLD.resolution) THEN
        RETURN OLD;
    END IF;
    IF NEW.status <> OLD.status AND (OLD.status, NEW.status) NOT IN
           (('OPEN', 'INVESTIGATING'), ('INVESTIGATING', 'RESOLVED'), ('RESOLVED', 'INVESTIGATING')) THEN
        RAISE EXCEPTION 'breaks: % -> % is not an allowed transition', OLD.status, NEW.status;
    END IF;
    IF OLD.status = 'RESOLVED' AND NEW.status = 'RESOLVED' THEN
        RAISE EXCEPTION 'breaks: break % is resolved; reopen it before changing it', OLD.id;
    END IF;
    PERFORM workflow_actor();
    NEW.version := OLD.version + 1;
    NEW.updated_at := now();
    RETURN NEW;
END
$$;

CREATE FUNCTION record_break_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.status, NEW.assignee, NEW.resolution) IS NOT DISTINCT FROM (OLD.status, OLD.assignee, OLD.resolution) THEN
        RETURN NULL;
    END IF;
    INSERT INTO break_events (break_id, actor, action, from_status, to_status, from_assignee, to_assignee,
                              resolution, correlation_id)
    VALUES (NEW.id, workflow_actor(),
            CASE WHEN NEW.status <> OLD.status THEN 'STATUS_CHANGED' ELSE 'ASSIGNED' END,
            OLD.status, NEW.status, OLD.assignee, NEW.assignee, NEW.resolution, workflow_correlation_id());
    RETURN NULL;
END
$$;

CREATE FUNCTION record_break_note() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.author IS DISTINCT FROM workflow_actor() THEN
        RAISE EXCEPTION 'break_notes: the author must be the actor named for this transaction';
    END IF;
    INSERT INTO break_events (break_id, actor, action, note_id, correlation_id)
    VALUES (NEW.break_id, NEW.author, 'NOTE_ADDED', NEW.id, workflow_correlation_id());
    RETURN NULL;
END
$$;

CREATE TRIGGER breaks_check_change BEFORE UPDATE ON breaks
    FOR EACH ROW EXECUTE FUNCTION check_break_change();
CREATE TRIGGER breaks_record_change AFTER UPDATE ON breaks
    FOR EACH ROW EXECUTE FUNCTION record_break_change();
CREATE TRIGGER break_notes_record AFTER INSERT ON break_notes
    FOR EACH ROW EXECUTE FUNCTION record_break_note();

CREATE TRIGGER break_notes_append_only BEFORE UPDATE OR DELETE ON break_notes
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER break_notes_no_truncate BEFORE TRUNCATE ON break_notes
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER break_events_append_only BEFORE UPDATE OR DELETE ON break_events
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER break_events_no_truncate BEFORE TRUNCATE ON break_events
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();
