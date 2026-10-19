package io.github.sanskar121543.reconciliation.ingest;

/** The file as a whole cannot be read (bad header, broken quoting, not a JSON array), so nothing is imported. */
public class MalformedFileException extends RuntimeException {

    public MalformedFileException(String message) {
        super(message);
    }
}
