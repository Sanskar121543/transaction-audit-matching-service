-- Statement lines now arrive through Kafka and are stored by whichever worker owns the account's partition.
-- Each worker adds the number of lines it stored to this counter in the same transaction, so exactly one
-- transaction sees it reach the statement's accepted-row count: that one completes the run.
ALTER TABLE runs ADD COLUMN statement_lines_received INT NOT NULL DEFAULT 0 CHECK (statement_lines_received >= 0);

-- When Kafka first acknowledged every line of the statement. A MATCHING run without it is waiting for the
-- statement to be uploaded again; with it, the workers are still matching.
ALTER TABLE runs ADD COLUMN statement_queued_at TIMESTAMPTZ;

-- Runs from before this version stored their statement inside the upload, so all of its lines are in and
-- nothing counted them: count them now, and date the queueing to the upload.
UPDATE runs r SET statement_lines_received = (SELECT count(*) FROM statement_lines s WHERE s.run_id = r.id),
                  statement_queued_at = (SELECT i.created_at FROM imports i
                                         WHERE i.run_id = r.id AND i.side = 'STATEMENT');

-- The last reason a worker could not store this run's lines, cleared when the run completes. Until then the
-- run stays MATCHING, and this says why.
ALTER TABLE runs ADD COLUMN matching_problem TEXT;
ALTER TABLE runs ADD COLUMN matching_problem_at TIMESTAMPTZ;
ALTER TABLE runs ADD CHECK ((matching_problem IS NULL) = (matching_problem_at IS NULL));
