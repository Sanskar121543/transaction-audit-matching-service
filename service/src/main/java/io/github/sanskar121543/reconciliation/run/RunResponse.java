package io.github.sanskar121543.reconciliation.run;

import java.time.Instant;

/**
 * A MATCHING run with {@code statementQueuedAt} null is waiting for its statement to be uploaded again (the
 * upload did not get every line onto Kafka); with it set, the workers are matching. {@code matchingProblem}
 * says why a worker last failed to store this run's lines, until the run completes.
 */
public record RunResponse(long id, String label, RunStatus status, long amountToleranceCents, int dateWindowDays,
        String createdBy, Instant createdAt, Instant statementQueuedAt, Instant completedAt, String matchingProblem,
        Instant matchingProblemAt, RunSummary summary) {

    static RunResponse of(Run run, RunSummary summary) {
        return new RunResponse(run.getId(), run.getLabel(), run.getStatus(), run.tolerance().amountCents(),
                run.tolerance().dateWindowDays(), run.getCreatedBy(), run.getCreatedAt(), run.getStatementQueuedAt(),
                run.getCompletedAt(), run.getMatchingProblem(), run.getMatchingProblemAt(), summary);
    }
}
