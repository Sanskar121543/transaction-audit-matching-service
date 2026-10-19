package io.github.sanskar121543.reconciliation.matching;

import static io.github.sanskar121543.reconciliation.matching.Items.ACCOUNT;
import static io.github.sanskar121543.reconciliation.matching.Items.ledger;
import static io.github.sanskar121543.reconciliation.matching.Items.line;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanskar121543.reconciliation.breaks.BreakType;
import io.github.sanskar121543.reconciliation.matching.RuleCheck.Result;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReconcilerTest {

    private final Reconciler reconciler = new Reconciler(new Tolerance(100, 3));

    @Test
    void anUnmatchedLedgerRecordIsMissingInBank() {
        LedgerItem record = ledger(1, 10, -40_00, "PAY-7");

        BreakDecision only = single(reconcile(List.of(record), List.of(), List.of()));

        assertThat(only.type()).isEqualTo(BreakType.MISSING_IN_BANK);
        assertThat(only.ledger()).isEqualTo(record);
        assertThat(only.line()).isNull();
        assertThat(only.exposureCents()).isEqualTo(40_00);
        assertThat(only.itemDate()).isEqualTo(record.date());
        assertThat(only.trace()).extracting(RuleCheck::result).containsExactly(Result.NO_CANDIDATE);
    }

    @Test
    void anUnmatchedBankLineIsMissingInLedger() {
        StatementItem fee = line(1, 12, -2_50, "");

        BreakDecision only = single(reconcile(List.of(), List.of(fee), List.of()));

        assertThat(only.type()).isEqualTo(BreakType.MISSING_IN_LEDGER);
        assertThat(only.line()).isEqualTo(fee);
        assertThat(only.ledger()).isNull();
        assertThat(only.exposureCents()).isEqualTo(2_50);
        assertThat(only.trace()).extracting(RuleCheck::result).containsExactly(Result.NO_REFERENCE);
    }

    @Test
    void theSameReferenceWithADifferentAmountIsOneAmountMismatchWithBothSides() {
        LedgerItem record = ledger(1, 10, 500_00, "INV-000123");
        StatementItem line = line(1, 11, 480_00, "inv 123");

        BreakDecision only = single(reconcile(List.of(record), List.of(line), List.of()));

        assertThat(only.type()).isEqualTo(BreakType.AMOUNT_MISMATCH);
        assertThat(only.ledger()).isEqualTo(record);
        assertThat(only.line()).isEqualTo(line);
        assertThat(only.exposureCents()).isEqualTo(20_00);
        assertThat(only.verdict()).contains("differ by 20.00");
    }

    @Test
    void aSecondBankLineForAnAlreadyMatchedPaymentIsADuplicate() {
        LedgerItem record = ledger(1, 10, 75_00, "INV-9");
        StatementItem original = line(1, 10, 75_00, "INV-9");
        StatementItem copy = line(2, 11, 75_00, "INV-9");
        MatchDecision match = new MatchDecision(record, original, Rule.EXACT);

        BreakDecision only = single(reconcile(List.of(), List.of(copy), List.of(match)));

        assertThat(only.type()).isEqualTo(BreakType.DUPLICATE);
        assertThat(only.line()).isEqualTo(copy);
        assertThat(only.verdict()).contains("Repeats statement row 1");
        assertThat(only.trace()).extracting(RuleCheck::result).containsExactly(Result.CANDIDATE_ALREADY_MATCHED);
    }

    @Test
    void aSecondLedgerRecordForAnAlreadyMatchedPaymentIsADuplicate() {
        LedgerItem original = ledger(1, 10, 75_00, "INV-9");
        LedgerItem copy = ledger(2, 10, 75_00, "INV-9");
        MatchDecision match = new MatchDecision(original, line(1, 10, 75_00, "INV-9"), Rule.EXACT);

        BreakDecision only = single(reconcile(List.of(copy), List.of(), List.of(match)));

        assertThat(only.type()).isEqualTo(BreakType.DUPLICATE);
        assertThat(only.ledger()).isEqualTo(copy);
        assertThat(only.verdict()).contains("Repeats ledger record #1");
    }

    @Test
    void aCopyOutsideTheDateWindowIsNotADuplicate() {
        LedgerItem record = ledger(1, 1, 75_00, "INV-9");
        MatchDecision match = new MatchDecision(record, line(1, 1, 75_00, "INV-9"), Rule.EXACT);

        BreakDecision only = single(reconcile(List.of(), List.of(line(2, 20, 75_00, "INV-9")), List.of(match)));

        assertThat(only.type()).isEqualTo(BreakType.MISSING_IN_LEDGER);
    }

    @Test
    void duplicatesAreClassifiedBeforeAmountMismatches() {
        // Partial payments: 100.00 matched, the bank posted the 100.00 twice, and the 250.00 never arrived.
        LedgerItem first = ledger(1, 10, 100_00, "INV-5");
        LedgerItem second = ledger(2, 12, 250_00, "INV-5");
        StatementItem paid = line(1, 10, 100_00, "INV-5");
        StatementItem postedTwice = line(2, 10, 100_00, "INV-5");
        MatchDecision match = new MatchDecision(first, paid, Rule.EXACT);

        List<BreakDecision> breaks = reconcile(List.of(second), List.of(postedTwice), List.of(match)).breaks();

        assertThat(breaks).extracting(BreakDecision::type)
                .containsExactlyInAnyOrder(BreakType.DUPLICATE, BreakType.MISSING_IN_BANK);
    }

    @Test
    void theResultDoesNotDependOnInputOrder() {
        List<LedgerItem> ledger = new ArrayList<>(List.of(ledger(1, 10, 100_00, "A-1"), ledger(2, 10, 300_00, "A-1"),
                ledger(3, 11, 50_00, "B-2"), ledger(4, 12, 70_00, "")));
        List<StatementItem> lines = new ArrayList<>(List.of(line(1, 10, 110_00, "A1"), line(2, 10, 290_00, "a-1"),
                line(3, 13, 60_00, "C-3"), line(4, 14, 70_00, "")));

        Reconciler.Outcome inOrder = reconcile(ledger, lines, List.of());
        Collections.reverse(ledger);
        Collections.reverse(lines);
        Reconciler.Outcome reversed = reconcile(ledger, lines, List.of());

        assertThat(reversed).isEqualTo(inOrder);
    }

    private Reconciler.Outcome reconcile(List<LedgerItem> ledger, List<StatementItem> lines, List<MatchDecision> matched) {
        return reconciler.reconcile(new AccountBook(ACCOUNT, ledger, lines, matched));
    }

    private static BreakDecision single(Reconciler.Outcome outcome) {
        assertThat(outcome.breaks()).hasSize(1);
        return outcome.breaks().getFirst();
    }
}
