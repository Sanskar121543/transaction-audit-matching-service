package io.github.sanskar121543.reconciliation.common;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * What this instance did, for Prometheus. Each count is added only once its transaction commits: a batch that
 * rolls back (a redelivery, a failover, a completed run) never shows up, so the totals agree with the database.
 * Every meter also carries the {@code app_instance} tag (application.yaml).
 */
@Component
public class ReconciliationMetrics {

    private final MeterRegistry registry;

    public ReconciliationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Rows of an uploaded file, accepted and rejected. */
    public void imported(String side, int accepted, int rejected) {
        afterCommit(() -> {
            rows(side, "accepted").increment(accepted);
            rows(side, "rejected").increment(rejected);
        });
    }

    public void linesStored(int count) {
        afterCommit(() -> Counter.builder("matching.statement.lines.stored")
                .description("Statement lines stored by the Kafka workers")
                .register(registry).increment(count));
    }

    /** Matches made, by the rule that made them (EXACT, TOLERANCE, NORMALIZED_REFERENCE). */
    public void matched(Map<String, Long> byRule) {
        afterCommit(() -> byRule.forEach((rule, count) -> Counter.builder("matching.matches")
                .description("Matches made, by rule").tag("rule", rule)
                .register(registry).increment(count)));
    }

    public void runCompleted(Map<String, Long> breaksByType, Duration endOfRunStep) {
        afterCommit(() -> {
            breaksByType.forEach((type, count) -> Counter.builder("matching.breaks")
                    .description("Breaks found, by type").tag("type", type)
                    .register(registry).increment(count));
            Counter.builder("matching.runs.completed").description("Runs completed").register(registry).increment();
            Timer.builder("matching.end.of.run")
                    .description("The end-of-run step: tolerance and normalized-reference rules, then breaks")
                    .register(registry).record(endOfRunStep);
        });
    }

    /** One change to a break's workflow: STATUS_CHANGED (tagged with the new status), ASSIGNED or NOTE_ADDED. */
    public void breakChanged(String action, String toStatus) {
        afterCommit(() -> Counter.builder("matching.break.changes")
                .description("Changes people made to breaks").tag("action", action).tag("to_status", toStatus)
                .register(registry).increment());
    }

    private Counter rows(String side, String outcome) {
        return Counter.builder("matching.import.rows").description("Rows of uploaded files")
                .tag("side", side).tag("outcome", outcome).register(registry);
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
