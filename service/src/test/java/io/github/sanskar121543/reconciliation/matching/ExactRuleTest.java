package io.github.sanskar121543.reconciliation.matching;

import static io.github.sanskar121543.reconciliation.matching.Items.ledger;
import static io.github.sanskar121543.reconciliation.matching.Items.line;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExactRuleTest {

    @Test
    void matchesTheSameReferenceAndTheSameAmount() {
        LedgerItem invoice = ledger(1, 10, 125_00, "INV-1");
        assertThat(ExactRule.pick(line(1, 11, 125_00, "INV-1"), List.of(invoice))).contains(invoice);
    }

    @Test
    void needsTheReferenceCharacterForCharacter() {
        assertThat(ExactRule.pick(line(1, 10, 125_00, "inv-1"), List.of(ledger(1, 10, 125_00, "INV-1")))).isEmpty();
    }

    @Test
    void needsTheAmountToTheCent() {
        assertThat(ExactRule.pick(line(1, 10, 125_01, "INV-1"), List.of(ledger(1, 10, 125_00, "INV-1")))).isEmpty();
    }

    @Test
    void neverMatchesABlankReference() {
        assertThat(ExactRule.pick(line(1, 10, 125_00, ""), List.of(ledger(1, 10, 125_00, "")))).isEmpty();
    }

    @Test
    void neverMatchesAcrossAccounts() {
        LedgerItem otherAccount = new LedgerItem(1, "ACC-2", LocalDate.of(2026, 10, 10), 125_00, "INV-1", "INV1");
        assertThat(ExactRule.pick(line(1, 10, 125_00, "INV-1"), List.of(otherAccount))).isEmpty();
    }

    @Test
    void prefersTheClosestDateThenTheLowestId() {
        LedgerItem far = ledger(1, 1, 50_00, "RENT");
        LedgerItem closeHigherId = ledger(3, 9, 50_00, "RENT");
        LedgerItem closeLowerId = ledger(2, 11, 50_00, "RENT");
        assertThat(ExactRule.pick(line(1, 10, 50_00, "RENT"), List.of(far, closeHigherId, closeLowerId)))
                .contains(closeLowerId);
    }
}
