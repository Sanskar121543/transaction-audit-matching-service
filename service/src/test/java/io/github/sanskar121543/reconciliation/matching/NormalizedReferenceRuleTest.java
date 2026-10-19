package io.github.sanskar121543.reconciliation.matching;

import static io.github.sanskar121543.reconciliation.matching.Items.ledger;
import static io.github.sanskar121543.reconciliation.matching.Items.line;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NormalizedReferenceRuleTest {

    private final Tolerance oneDollarThreeDays = new Tolerance(100, 3);

    @Test
    void matchesAReformattedReferenceOutsideTheDateWindow() {
        LedgerItem invoice = ledger(1, 1, 980_00, "INV-000123");
        assertThat(pick(line(1, 20, 980_00, "inv 123"), invoice)).contains(invoice);
    }

    @Test
    void allowsTheAmountToDifferWithinTheTolerance() {
        LedgerItem invoice = ledger(1, 1, 980_00, "INV-000123");
        assertThat(pick(line(1, 20, 979_50, "INV123"), invoice)).contains(invoice);
    }

    @Test
    void refusesAnAmountOutsideTheTolerance() {
        assertThat(pick(line(1, 20, 970_00, "inv 123"), ledger(1, 1, 980_00, "INV-000123"))).isEmpty();
    }

    @Test
    void refusesADifferentReference() {
        assertThat(pick(line(1, 20, 980_00, "inv 124"), ledger(1, 1, 980_00, "INV-000123"))).isEmpty();
    }

    @Test
    void neverMatchesAReferenceWithoutLettersOrDigits() {
        assertThat(pick(line(1, 20, 980_00, "--"), ledger(1, 1, 980_00, "//"))).isEmpty();
    }

    @Test
    void refusesTheOppositeSign() {
        Tolerance huge = new Tolerance(100_000, 3);
        assertThat(NormalizedReferenceRule.pick(line(1, 10, -50, "R1"), List.of(ledger(1, 10, 50, "R-1")), huge))
                .isEmpty();
    }

    @Test
    void prefersTheClosestDate() {
        LedgerItem january = ledger(1, 1, 50_00, "RENT-10");
        LedgerItem later = ledger(2, 25, 50_00, "RENT-10");
        assertThat(pick(line(1, 30, 50_00, "rent 10"), january, later)).contains(later);
    }

    private Optional<LedgerItem> pick(StatementItem line, LedgerItem... ledger) {
        return NormalizedReferenceRule.pick(line, List.of(ledger), oneDollarThreeDays);
    }
}
