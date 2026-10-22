package io.github.sanskar121543.reconciliation.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.jdbc.core.JdbcTemplate;

/** What must hold in the database for every completed run, however its lines reached the workers. */
public final class RunInvariants {

    private RunInvariants() {
    }

    /**
     * Every accepted line stored once, the completion counter equal to that count, and each account's lines
     * stored in file order: identity ids follow insert order, and one account's inserts never overlap
     * (MatchStore.lockAccounts).
     */
    public static void assertLinesStoredOnceInFileOrder(JdbcTemplate jdbc, long runId, long acceptedLines) {
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT row_number) FROM statement_lines WHERE run_id = ?",
                Long.class, runId)).as("distinct lines stored").isEqualTo(acceptedLines);
        assertThat(jdbc.queryForObject("SELECT statement_lines_received FROM runs WHERE id = ?", Long.class, runId))
                .as("the completion counter").isEqualTo(acceptedLines);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM (
                    SELECT row_number, lag(row_number) OVER (PARTITION BY account_id ORDER BY id) AS previous
                    FROM statement_lines WHERE run_id = ?) t
                WHERE previous > row_number
                """, Long.class, runId)).as("lines stored out of file order").isZero();
    }

    public static void assertEveryRecordMatchedOrBrokenOnce(JdbcTemplate jdbc, long runId) {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM ledger_records l WHERE l.run_id = ?
                  AND (SELECT count(*) FROM matches m WHERE m.ledger_record_id = l.id)
                    + (SELECT count(*) FROM breaks b WHERE b.ledger_record_id = l.id) <> 1
                """, Long.class, runId)).as("ledger records not in exactly one match or break").isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM statement_lines s WHERE s.run_id = ?
                  AND (SELECT count(*) FROM matches m WHERE m.statement_line_id = s.id)
                    + (SELECT count(*) FROM breaks b WHERE b.statement_line_id = s.id) <> 1
                """, Long.class, runId)).as("statement lines not in exactly one match or break").isZero();
    }
}
