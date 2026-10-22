package io.github.sanskar121543.reconciliation.breaks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sanskar121543.reconciliation.run.ReconciliationIT;
import io.github.sanskar121543.reconciliation.support.ApiClient;
import io.github.sanskar121543.reconciliation.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The trail is kept by the database, not by the service: these changes go straight to Postgres, the way someone
 * with psql would make them, and the triggers still record or refuse them.
 */
@IntegrationTest
class AuditTrailIT {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    ApiClient api;
    long breakId;

    @BeforeEach
    void completedRun() {
        api = ApiClient.forPort(port);
        long runId = api.createRun("audit trail");
        api.uploadLedger(runId, ReconciliationIT.LEDGER);
        api.uploadStatement(runId, ReconciliationIT.STATEMENT);
        api.awaitCompleted(runId);
        breakId = jdbc.queryForObject("SELECT id FROM breaks WHERE run_id = ? AND type = 'MISSING_IN_BANK'",
                Long.class, runId);
    }

    @Test
    void aChangeThatNamesNobodyIsRefused() {
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE breaks SET status = 'INVESTIGATING', assignee = 'mallory' WHERE id = ?", breakId))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("matching.actor");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO break_notes (break_id, author, body) VALUES (?, 'mallory', 'nobody saw this')
                """, breakId)).isInstanceOf(DataAccessException.class).hasMessageContaining("matching.actor");
        assertThat(events()).isZero();
    }

    @Test
    void aChangeMadeOutsideTheServiceIsRecordedAndMakesOlderReadsStale() {
        asActor("dba", () -> jdbc.update(
                "UPDATE breaks SET status = 'INVESTIGATING', assignee = 'erin' WHERE id = ?", breakId));

        Map<String, Object> event = jdbc.queryForMap("SELECT * FROM break_events WHERE break_id = ?", breakId);
        assertThat(event).containsEntry("actor", "dba").containsEntry("action", "STATUS_CHANGED")
                .containsEntry("from_status", "OPEN").containsEntry("to_status", "INVESTIGATING")
                .containsEntry("to_assignee", "erin");
        assertThat(jdbc.queryForObject("SELECT version FROM breaks WHERE id = ?", Long.class, breakId)).isEqualTo(1);
        assertThat(api.post("/v1/breaks/" + breakId + "/assign", Map.of("version", 0, "assignee", "frank"))
                .status()).isEqualTo(409);
    }

    @Test
    void theActorDoesNotOutliveItsTransaction() {
        asActor("dba", () -> jdbc.update("UPDATE breaks SET assignee = 'erin' WHERE id = ?", breakId));

        // Whichever pooled connection this lands on, the earlier SET LOCAL has ended.
        assertThatThrownBy(() -> jdbc.update("UPDATE breaks SET assignee = 'grace' WHERE id = ?", breakId))
                .hasMessageContaining("matching.actor");
    }

    @Test
    void theRecordedFactsOfABreakCannotChange() {
        assertThatThrownBy(() -> asActor("dba",
                () -> jdbc.update("UPDATE breaks SET exposure_cents = 0 WHERE id = ?", breakId)))
                .hasMessageContaining("only status, assignee and resolution");
        assertThatThrownBy(() -> asActor("dba",
                () -> jdbc.update("UPDATE breaks SET type = 'DUPLICATE' WHERE id = ?", breakId)))
                .hasMessageContaining("only status, assignee and resolution");
    }

    @Test
    void theWorkflowIsEnforcedBelowTheService() {
        assertThatThrownBy(() -> asActor("dba", () -> jdbc.update(
                "UPDATE breaks SET status = 'RESOLVED', assignee = 'erin', resolution = 'OTHER' WHERE id = ?",
                breakId))).hasMessageContaining("OPEN -> RESOLVED is not an allowed transition");
        assertThatThrownBy(() -> asActor("dba", () -> jdbc.update(
                "UPDATE breaks SET status = 'INVESTIGATING' WHERE id = ?", breakId)))
                .as("an investigation needs an owner").isInstanceOf(DataAccessException.class);
        assertThat(events()).isZero();
    }

    @Test
    void aNoteCannotBeWrittenInSomeoneElsesName() {
        assertThatThrownBy(() -> asActor("dba", () -> jdbc.update(
                "INSERT INTO break_notes (break_id, author, body) VALUES (?, 'ops', 'not me')", breakId)))
                .hasMessageContaining("author must be the actor");
    }

    @ParameterizedTest
    @ValueSource(strings = {"break_events", "break_notes"})
    void theTrailAndTheNotesAreAppendOnly(String table) {
        api.post("/v1/breaks/" + breakId + "/investigate", Map.of("version", 0, "note", "on it"));

        assertThatThrownBy(() -> jdbc.update("UPDATE " + table + " SET break_id = break_id WHERE break_id = ?",
                breakId)).isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM " + table + " WHERE break_id = ?", breakId))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE " + table + " CASCADE"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    }

    private void asActor(String actor, Runnable change) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.queryForList("SELECT set_config('matching.actor', ?, true)", actor);
            change.run();
        });
    }

    private long events() {
        return jdbc.queryForObject("SELECT count(*) FROM break_events WHERE break_id = ?", Long.class, breakId);
    }
}
