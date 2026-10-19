package io.github.sanskar121543.reconciliation.matching;

import static io.github.sanskar121543.reconciliation.matching.Items.ledger;
import static io.github.sanskar121543.reconciliation.matching.Items.line;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ToleranceRuleTest {

    private final Tolerance oneDollarThreeDays = new Tolerance(100, 3);

    @Test
    void matchesAnAmountWithinTheToleranceAndADateWithinTheWindowWhateverTheReference() {
        LedgerItem invoice = ledger(1, 10, 250_00, "INV-77");
        assertThat(pick(line(1, 13, 249_25, "WIRE 1188"), invoice)).contains(invoice);
    }

    @Test
    void refusesAnAmountOutsideTheTolerance() {
        assertThat(pick(line(1, 10, 248_99, "x"), ledger(1, 10, 250_00, "y"))).isEmpty();
    }

    @Test
    void refusesADateOutsideTheWindow() {
        assertThat(pick(line(1, 14, 250_00, "x"), ledger(1, 10, 250_00, "y"))).isEmpty();
    }

    @Test
    void refusesTheOppositeSign() {
        Tolerance huge = new Tolerance(100_000, 3);
        assertThat(ToleranceRule.pick(line(1, 10, -50, "x"), List.of(ledger(1, 10, 50, "y")), huge)).isEmpty();
    }

    @Test
    void aMatchingReferenceBeatsACloserAmount() {
        LedgerItem closerAmount = ledger(1, 10, 100_00, "INV-1");
        LedgerItem sameReference = ledger(2, 10, 100_80, "INV-2");
        assertThat(pick(line(1, 10, 100_00, "inv 2"), closerAmount, sameReference)).contains(sameReference);
    }

    @Test
    void thenTheSmallestAmountDifferenceThenTheClosestDateThenTheLowestId() {
        LedgerItem farAmount = ledger(1, 10, 100_90, "a");
        LedgerItem nearAmountFarDate = ledger(2, 12, 100_10, "b");
        LedgerItem nearAmountNearDateHighId = ledger(4, 10, 100_10, "c");
        LedgerItem nearAmountNearDateLowId = ledger(3, 10, 100_10, "d");
        assertThat(pick(line(1, 10, 100_00, "z"), farAmount, nearAmountFarDate, nearAmountNearDateHighId,
                nearAmountNearDateLowId)).contains(nearAmountNearDateLowId);
    }

    @Test
    void theAgreeingReferenceVariantIgnoresCandidatesWithAnotherReference() {
        LedgerItem other = ledger(1, 10, 100_00, "INV-1");
        LedgerItem same = ledger(2, 10, 100_50, "INV-2");
        StatementItem line = line(1, 10, 100_00, "inv 2");

        assertThat(ToleranceRule.pickWithAgreeingReference(line, List.of(other, same), oneDollarThreeDays)).contains(same);
        assertThat(ToleranceRule.pickWithAgreeingReference(line, List.of(other), oneDollarThreeDays)).isEmpty();
        assertThat(ToleranceRule.pickWithAgreeingReference(line(2, 10, 100_00, ""), List.of(other), oneDollarThreeDays))
                .isEmpty();
    }

    private Optional<LedgerItem> pick(StatementItem line, LedgerItem... ledger) {
        return ToleranceRule.pick(line, List.of(ledger), oneDollarThreeDays);
    }
}
