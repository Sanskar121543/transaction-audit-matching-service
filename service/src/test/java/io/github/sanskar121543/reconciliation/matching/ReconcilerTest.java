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
        assertThat(only.trace()).extracting(RuleCheck::rule)
                .containsExactly(Rule.EXACT, Rule.TOLERANCE, Rule.NORMALIZED_REFERENCE);
        assertThat(only.trace()).extracting(RuleCheck::result)
                .containsExactly(Result.NO_CANDIDATE, Result.NO_CANDIDATE, Result.NO_CANDIDATE);
    }

    @Test
    void anUnmatchedBankLineIsMissingInLedger() {
        StatementItem fee = line(1, 12, -2_50, "");

        BreakDecision only = single(reconcile(List.of(), List.of(fee), List.of()));

        assertThat(only.type()).isEqualTo(BreakType.MISSING_IN_LEDGER);
        assertThat(only.line()).isEqualTo(fee);
        assertThat(only.ledger()).isNull();
        assertThat(only.exposureCents()).isEqualTo(2_50);
        assertThat(only.trace()).extracting(RuleCheck::result)
                .containsExactly(Result.NO_REFERENCE, Result.NO_CANDIDATE, Result.NO_REFERENCE);
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
        assertThat(only.trace()).extracting(RuleCheck::result).containsExactly(Result.CANDIDATE_ALREADY_MATCHED,
                Result.NO_CANDIDATE, Result.CANDIDATE_ALREADY_MATCHED);
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
    void theToleranceRuleRunsBeforeTheNormalizedReferenceRule() {
        LedgerItem record = ledger(1, 10, 100_00, "INV-8");
        StatementItem line = line(1, 11, 100_00, "inv 8");

        Reconciler.Outcome outcome = reconcile(List.of(record), List.of(line), List.of());

        assertThat(outcome.breaks()).isEmpty();
        assertThat(outcome.matches()).containsExactly(new MatchDecision(record, line, Rule.TOLERANCE));
    }

    @Test
    void theNormalizedReferenceRuleMatchesWhatTheDateWindowLeftOut() {
        LedgerItem record = ledger(1, 1, 100_00, "INV-8");
        StatementItem late = line(1, 20, 100_00, "inv 8");

        Reconciler.Outcome outcome = reconcile(List.of(record), List.of(late), List.of());

        assertThat(outcome.matches()).containsExactly(new MatchDecision(record, late, Rule.NORMALIZED_REFERENCE));
    }

    @Test
    void aCopyOfALineTheFuzzyRulesMatchedIsStillADuplicate() {
        LedgerItem record = ledger(1, 10, 100_00, "INV-8");
        StatementItem first = line(1, 10, 99_50, "INV-8");
        StatementItem copy = line(2, 10, 99_50, "INV-8");

        Reconciler.Outcome outcome = reconcile(List.of(record), List.of(first, copy), List.of());

        assertThat(outcome.matches()).containsExactly(new MatchDecision(record, first, Rule.TOLERANCE));
        assertThat(single(outcome).type()).isEqualTo(BreakType.DUPLICATE);
        assertThat(single(outcome).line()).isEqualTo(copy);
    }

    @Test
    void explainsTheClosestCandidateForEachRule() {
        LedgerItem record = ledger(1, 1, 100_00, "INV-8");
        StatementItem line = line(1, 20, 103_00, "inv 8");

        BreakDecision mismatch = single(reconcile(List.of(record), List.of(line), List.of()));

        assertThat(mismatch.type()).isEqualTo(BreakType.AMOUNT_MISMATCH);
        RuleCheck exact = mismatch.trace().get(0);
        RuleCheck tolerance = mismatch.trace().get(1);
        RuleCheck normalized = mismatch.trace().get(2);
        assertThat(exact.result()).isEqualTo(Result.NO_CANDIDATE);
        assertThat(tolerance.result()).isEqualTo(Result.OUTSIDE_TOLERANCE);
        assertThat(tolerance.ledgerRecordId()).isEqualTo(1L);
        assertThat(tolerance.amountDifferenceCents()).isEqualTo(-3_00);
        assertThat(tolerance.dateDifferenceDays()).isEqualTo(19);
        assertThat(tolerance.detail()).isEqualTo(
                "The closest unmatched amount is Ledger record #1, which differs by 3.00 (tolerance 1.00).");
        assertThat(normalized.result()).isEqualTo(Result.OUTSIDE_TOLERANCE);
    }

    @Test
    void explainsWhenOnlyTheDateWindowWasMissed() {
        BreakDecision missing = reconcile(List.of(ledger(1, 1, 100_00, "A")), List.of(line(1, 9, 100_00, "B")),
                List.of()).breaks().getFirst();

        RuleCheck tolerance = missing.trace().get(1);
        assertThat(tolerance.result()).isEqualTo(Result.OUTSIDE_DATE_WINDOW);
        assertThat(tolerance.detail()).isEqualTo(
                "Ledger record #1 is within the amount tolerance, but 8 days apart (window 3 days).");
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
