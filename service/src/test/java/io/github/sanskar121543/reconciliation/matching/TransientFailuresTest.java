package io.github.sanskar121543.reconciliation.matching;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.transaction.CannotCreateTransactionException;

class TransientFailuresTest {

    @Test
    void connectionLossLockTimeoutsDeadlocksAndAnEndedIdleSessionAreRetried() {
        assertThat(TransientFailures.isTransient(new CannotAcquireLockException("lock"))).isTrue();
        assertThat(TransientFailures.isTransient(new CannotCreateTransactionException("pool"))).isTrue();
        assertThat(TransientFailures.isTransient(sql("08006"))).isTrue();
        assertThat(TransientFailures.isTransient(sql("40P01"))).isTrue();
        assertThat(TransientFailures.isTransient(sql("57P01"))).isTrue();
        // Spring does not translate 25P03: it arrives uncategorised, and only its SQLState says what happened.
        assertThat(TransientFailures.isTransient(sql("25P03"))).isTrue();
    }

    @Test
    void bugsAndBadDataAreNot() {
        assertThat(TransientFailures.isTransient(new DataIntegrityViolationException("dup"))).isFalse();
        assertThat(TransientFailures.isTransient(sql("23505"))).isFalse();
        assertThat(TransientFailures.isTransient(sql("P0001"))).isFalse();
        assertThat(TransientFailures.isTransient(new IllegalStateException("bug"))).isFalse();
    }

    @Test
    void theDescriptionNamesTheFailureButQuotesNothingFromIt() {
        String description = TransientFailures.describe(sql("23505"));

        assertThat(description).contains("UncategorizedSQLException").contains("23505").doesNotContain("secret");
    }

    private static UncategorizedSQLException sql(String state) {
        return new UncategorizedSQLException("storing lines", "INSERT ...",
                new SQLException("value 'secret' violates something", state));
    }
}
