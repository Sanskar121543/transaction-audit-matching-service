package io.github.sanskar121543.reconciliation.matching;

import java.sql.SQLException;
import java.util.Set;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.TransactionException;

/**
 * Whether a failure is worth retrying as is: the same work can succeed a moment later, unlike a bug or a bad
 * record. Spring's own exception types cover most cases; Postgres states it does not translate (an idle
 * session the server ended, for one, arrives as an uncategorised error) are recognised from the SQLState
 * anywhere in the cause chain.
 */
final class TransientFailures {

    // 08 connection, 40 serialization failure or deadlock, 53 insufficient resources, 57 operator intervention
    // (shutdown, query cancelled).
    private static final Set<String> TRANSIENT_CLASSES = Set.of("08", "40", "53", "57");
    // Idle-in-transaction and idle-session timeouts, and lock_timeout.
    private static final Set<String> TRANSIENT_STATES = Set.of("25P03", "25P04", "55P03");

    private TransientFailures() {
    }

    static boolean isTransient(Throwable failure) {
        // Bounded, in case a cause chain loops back on itself.
        Throwable t = failure;
        for (int depth = 0; t != null && depth < 20; depth++, t = t.getCause()) {
            if (t instanceof TransientDataAccessException || t instanceof RecoverableDataAccessException
                    || t instanceof DataAccessResourceFailureException || t instanceof TransactionException) {
                return true;
            }
            if (t instanceof SQLException sql && isTransientState(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    /**
     * What went wrong, safe to show through the API: the exception type and SQLState only. Their messages can
     * quote imported data, so they go to the log, not here.
     */
    static String describe(Throwable failure) {
        String state = null;
        Throwable t = failure;
        for (int depth = 0; t != null && state == null && depth < 20; depth++, t = t.getCause()) {
            if (t instanceof SQLException sql) {
                state = sql.getSQLState();
            }
        }
        return failure.getClass().getSimpleName() + (state == null ? "" : " (SQLState " + state + ")")
                + " while storing or matching statement lines; the instance's log has the details";
    }

    private static boolean isTransientState(String state) {
        return state != null && state.length() == 5
                && (TRANSIENT_STATES.contains(state) || TRANSIENT_CLASSES.contains(state.substring(0, 2)));
    }
}
