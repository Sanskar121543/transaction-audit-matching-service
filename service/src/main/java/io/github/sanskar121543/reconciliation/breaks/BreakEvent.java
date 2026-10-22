package io.github.sanskar121543.reconciliation.breaks;

import java.time.Instant;

/**
 * One entry in a break's audit trail, written by the database in the transaction that made the change.
 * {@code actor} is the API client that made it; {@code correlationId} is the request's X-Correlation-Id. A
 * status change or assignment fills the from/to fields; a note fills {@code noteId} and {@code note}.
 */
public record BreakEvent(long id, long breakId, String actor, Action action, BreakStatus fromStatus,
        BreakStatus toStatus, String fromAssignee, String toAssignee, BreakResolution resolution, Long noteId,
        String note, String correlationId, Instant at) {

    public enum Action {
        STATUS_CHANGED,
        ASSIGNED,
        NOTE_ADDED
    }
}
