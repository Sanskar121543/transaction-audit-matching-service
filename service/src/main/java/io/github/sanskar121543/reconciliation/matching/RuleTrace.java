package io.github.sanskar121543.reconciliation.matching;

import io.github.sanskar121543.reconciliation.common.Money;
import io.github.sanskar121543.reconciliation.matching.RuleCheck.Result;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Explains, rule by rule, why an unmatched record did not match: what each rule looked for and the
 * closest thing it found. It looks at the account as it stood when matching finished.
 */
final class RuleTrace {

    private final List<LedgerItem> unmatchedLedger;
    private final List<StatementItem> unmatchedLines;
    private final List<MatchDecision> matched;

    RuleTrace(List<LedgerItem> unmatchedLedger, List<StatementItem> unmatchedLines, List<MatchDecision> matched) {
        this.unmatchedLedger = unmatchedLedger;
        this.unmatchedLines = unmatchedLines;
        this.matched = matched;
    }

    List<RuleCheck> forLine(StatementItem line) {
        List<RuleCheck> checks = new ArrayList<>();
        checks.add(exactForLine(line));
        return checks;
    }

    List<RuleCheck> forLedger(LedgerItem record) {
        List<RuleCheck> checks = new ArrayList<>();
        checks.add(exactForLedger(record));
        return checks;
    }

    private RuleCheck exactForLine(StatementItem line) {
        if (line.reference().isEmpty()) {
            return check(Rule.EXACT, Result.NO_REFERENCE, null, null, null, null, "The bank line has no reference.");
        }
        Optional<MatchDecision> taken = matched.stream()
                .filter(m -> m.ledger().reference().equals(line.reference())
                        && m.ledger().amountCents() == line.amountCents())
                .min(Comparator.comparingLong(m -> m.ledger().id()));
        if (taken.isPresent()) {
            MatchDecision m = taken.get();
            return check(Rule.EXACT, Result.CANDIDATE_ALREADY_MATCHED, m.ledger().id(), m.line().id(), 0L, null,
                    "Ledger record #" + m.ledger().id() + " has the same reference and amount, but it is already matched"
                            + " to statement row " + m.line().rowNumber() + ".");
        }
        Optional<LedgerItem> sameReference = Stream.concat(unmatchedLedger.stream(), matched.stream().map(MatchDecision::ledger))
                .filter(l -> l.reference().equals(line.reference()))
                .min(byAmountDistance(line.amountCents()));
        if (sameReference.isEmpty()) {
            return check(Rule.EXACT, Result.NO_CANDIDATE, null, null, null, null,
                    "No ledger record in this account has the same reference.");
        }
        LedgerItem closest = sameReference.get();
        long difference = closest.amountCents() - line.amountCents();
        return check(Rule.EXACT, Result.AMOUNT_DIFFERS, closest.id(), null, difference, null,
                "Ledger record #" + closest.id() + " has the same reference, but its amount differs by "
                        + Money.format(Math.abs(difference)) + ".");
    }

    private RuleCheck exactForLedger(LedgerItem record) {
        if (record.reference().isEmpty()) {
            return check(Rule.EXACT, Result.NO_REFERENCE, null, null, null, null, "The ledger record has no reference.");
        }
        Optional<MatchDecision> taken = matched.stream()
                .filter(m -> m.line().reference().equals(record.reference())
                        && m.line().amountCents() == record.amountCents())
                .min(Comparator.comparingInt(m -> m.line().rowNumber()));
        if (taken.isPresent()) {
            MatchDecision m = taken.get();
            return check(Rule.EXACT, Result.CANDIDATE_ALREADY_MATCHED, m.ledger().id(), m.line().id(), 0L, null,
                    "Statement row " + m.line().rowNumber() + " has the same reference and amount, but it is already"
                            + " matched to ledger record #" + m.ledger().id() + ".");
        }
        Optional<StatementItem> sameReference = Stream.concat(unmatchedLines.stream(), matched.stream().map(MatchDecision::line))
                .filter(s -> s.reference().equals(record.reference()))
                .min(Comparator.comparingLong((StatementItem s) -> Math.abs(s.amountCents() - record.amountCents()))
                        .thenComparingInt(StatementItem::rowNumber));
        if (sameReference.isEmpty()) {
            return check(Rule.EXACT, Result.NO_CANDIDATE, null, null, null, null,
                    "No bank line in this account has the same reference.");
        }
        StatementItem closest = sameReference.get();
        long difference = record.amountCents() - closest.amountCents();
        return check(Rule.EXACT, Result.AMOUNT_DIFFERS, null, closest.id(), difference, null,
                "Statement row " + closest.rowNumber() + " has the same reference, but its amount differs by "
                        + Money.format(Math.abs(difference)) + ".");
    }

    private static Comparator<LedgerItem> byAmountDistance(long amountCents) {
        return Comparator.comparingLong((LedgerItem l) -> Math.abs(l.amountCents() - amountCents))
                .thenComparingLong(LedgerItem::id);
    }

    private static RuleCheck check(Rule rule, Result result, Long ledgerId, Long lineId, Long amountDifference,
            Long dateDifference, String detail) {
        return new RuleCheck(rule, result, ledgerId, lineId, amountDifference, dateDifference, detail);
    }
}
