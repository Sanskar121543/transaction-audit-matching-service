package io.github.sanskar121543.reconciliation.ingest;

/**
 * The statement is recorded but Kafka did not acknowledge every line. Nothing is lost: uploading the same
 * file again queues the lines again, and workers skip the ones they already have.
 */
public class StatementNotQueuedException extends RuntimeException {

    public StatementNotQueuedException(long runId, Throwable cause) {
        super("Run " + runId + ": the statement's lines could not all be queued (" + cause.getMessage() + ")", cause);
    }
}
