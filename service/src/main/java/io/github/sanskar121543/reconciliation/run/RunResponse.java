package io.github.sanskar121543.reconciliation.run;

import java.time.Instant;

public record RunResponse(long id, String label, RunStatus status, long amountToleranceCents, int dateWindowDays,
        String createdBy, Instant createdAt, Instant completedAt, RunSummary summary) {

    static RunResponse of(Run run, RunSummary summary) {
        return new RunResponse(run.getId(), run.getLabel(), run.getStatus(), run.tolerance().amountCents(),
                run.tolerance().dateWindowDays(), run.getCreatedBy(), run.getCreatedAt(), run.getCompletedAt(),
                summary);
    }
}
