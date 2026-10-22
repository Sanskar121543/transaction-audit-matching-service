package io.github.sanskar121543.reconciliation.run;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sanskar121543.reconciliation.support.ApiClient;
import io.github.sanskar121543.reconciliation.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** The imported data and the matching results are facts: the database refuses to change them. */
@IntegrationTest
class SourceImmutabilityIT {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    long runId;

    @BeforeEach
    void completedRun() {
        ApiClient api = ApiClient.forPort(port);
        runId = api.createRun("immutability");
        api.uploadLedger(runId, ReconciliationIT.LEDGER);
        api.uploadStatement(runId, ReconciliationIT.STATEMENT);
        api.awaitCompleted(runId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"imports", "ledger_records", "statement_lines", "matches"})
    void appendOnlyTablesRefuseUpdateDeleteAndTruncate(String table) {
        assertThatThrownBy(() -> jdbc.update("UPDATE " + table + " SET run_id = run_id WHERE run_id = ?", runId))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM " + table + " WHERE run_id = ?", runId))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE " + table + " CASCADE"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    }

    @Test
    void breaksCannotBeDeleted() {
        assertThatThrownBy(() -> jdbc.update("DELETE FROM breaks WHERE run_id = ?", runId))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    }

    @Test
    void aRecordIsNeverBothMatchedAndABreak() {
        Long brokenLedgerId = jdbc.queryForObject("""
                SELECT ledger_record_id FROM breaks WHERE run_id = ? AND type = 'MISSING_IN_BANK'
                """, Long.class, runId);
        Long matchedLine = jdbc.queryForObject("""
                SELECT m.statement_line_id FROM matches m JOIN ledger_records l ON l.id = m.ledger_record_id
                WHERE m.run_id = ? AND l.external_id = 'L-1'
                """, Long.class, runId);
        Long unmatchedLine = jdbc.queryForObject("""
                SELECT statement_line_id FROM breaks WHERE run_id = ? AND type = 'MISSING_IN_LEDGER'
                """, Long.class, runId);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO matches (run_id, account_id, ledger_record_id, statement_line_id, rule, matched_by)
                VALUES (?, 'ACC-1', ?, ?, 'EXACT', 'test')
                """, runId, brokenLedgerId, unmatchedLine)).hasMessageContaining("already a break");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO breaks (run_id, type, account_id, statement_line_id, exposure_cents, item_date, rule_trace)
                VALUES (?, 'MISSING_IN_LEDGER', 'ACC-1', ?, 1, '2026-10-01', '{}')
                """, runId, matchedLine)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void aMatchCannotPairRecordsFromDifferentAccounts() {
        Long acc2Ledger = jdbc.queryForObject(
                "SELECT ledger_record_id FROM breaks WHERE run_id = ? AND type = 'DUPLICATE' AND account_id = 'ACC-2'",
                Long.class, runId);
        Long acc1Line = jdbc.queryForObject(
                "SELECT statement_line_id FROM breaks WHERE run_id = ? AND type = 'MISSING_IN_LEDGER'", Long.class, runId);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO matches (run_id, account_id, ledger_record_id, statement_line_id, rule, matched_by)
                VALUES (?, 'ACC-1', ?, ?, 'EXACT', 'test')
                """, runId, acc2Ledger, acc1Line)).isInstanceOf(DataAccessException.class);
    }
}
