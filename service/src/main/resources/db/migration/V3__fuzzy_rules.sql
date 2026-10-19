ALTER TABLE matches DROP CONSTRAINT matches_rule_check;
ALTER TABLE matches ADD CONSTRAINT matches_rule_check
    CHECK (rule IN ('EXACT', 'TOLERANCE', 'NORMALIZED_REFERENCE'));
