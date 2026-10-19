package io.github.sanskar121543.reconciliation.matching;

import io.github.sanskar121543.reconciliation.breaks.BreakType;
import io.github.sanskar121543.reconciliation.common.Money;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * The end-of-run step for one account, once every statement line is in. The exact rule (a) already ran
 * on each line as it arrived; this runs rule (b), then rule (c), over every line still unmatched. Running
 * the fuzzy rules only now means they can never take a ledger record that a later line matches exactly.
 * Rule (b) itself goes over the lines twice: first only pairs whose references also agree, then the rest.
 * Whatever is still unmatched then becomes a break, classified in this order:
 * <ol>
 * <li>DUPLICATE: a second copy (same normalised reference and amount, dates within the window) of a
 * record that is matched;</li>
 * <li>AMOUNT_MISMATCH: a ledger record and a bank line with the same normalised reference;</li>
 * <li>MISSING_IN_LEDGER / MISSING_IN_BANK: everything left.</li>
 * </ol>
 * Pure and deterministic: lines are visited in row order, ledger records in id order, and every choice
 * has an explicit tie-break.
 */
public final class Reconciler {

    private final Tolerance tolerance;

    public Reconciler(Tolerance tolerance) {
        this.tolerance = tolerance;
    }

    public record Outcome(List<MatchDecision> matches, List<BreakDecision> breaks) {
    }

    public Outcome reconcile(AccountBook book) {
        List<LedgerItem> ledger = new ArrayList<>(book.unmatchedLedger());
        ledger.sort(Comparator.comparingLong(LedgerItem::id));
        List<StatementItem> lines = new ArrayList<>(book.unmatchedLines());
        lines.sort(Comparator.comparingInt(StatementItem::rowNumber));
        List<MatchDecision> matched = new ArrayList<>(book.relatedMatches());
        List<MatchDecision> newMatches = new ArrayList<>();
        matchPass(Rule.TOLERANCE, lines, ledger, newMatches,
                line -> ToleranceRule.pickWithAgreeingReference(line, ledger, tolerance));
        matchPass(Rule.TOLERANCE, lines, ledger, newMatches,
                line -> ToleranceRule.pick(line, ledger, tolerance));
        matchPass(Rule.NORMALIZED_REFERENCE, lines, ledger, newMatches,
                line -> NormalizedReferenceRule.pick(line, ledger, tolerance));
        matched.addAll(newMatches);

        RuleTrace trace = new RuleTrace(tolerance, List.copyOf(ledger), List.copyOf(lines), List.copyOf(matched));
        List<BreakDecision> breaks = new ArrayList<>();
        classifyDuplicates(lines, ledger, matched, trace, breaks);
        classifyAmountMismatches(lines, ledger, trace, breaks);
        for (StatementItem line : lines) {
            breaks.add(new BreakDecision(BreakType.MISSING_IN_LEDGER, book.accountId(), null, line,
                    Math.abs(line.amountCents()), line.date(), "No ledger record matched this bank line.",
                    trace.forLine(line)));
        }
        for (LedgerItem record : ledger) {
            breaks.add(new BreakDecision(BreakType.MISSING_IN_BANK, book.accountId(), record, null,
                    Math.abs(record.amountCents()), record.date(), "No bank line matched this ledger record.",
                    trace.forLedger(record)));
        }
        return new Outcome(newMatches, breaks);
    }

    private static void matchPass(Rule rule, List<StatementItem> lines, List<LedgerItem> ledger,
            List<MatchDecision> matches, Function<StatementItem, Optional<LedgerItem>> pick) {
        for (Iterator<StatementItem> it = lines.iterator(); it.hasNext();) {
            StatementItem line = it.next();
            Optional<LedgerItem> match = pick.apply(line);
            if (match.isPresent()) {
                matches.add(new MatchDecision(match.get(), line, rule));
                ledger.remove(match.get());
                it.remove();
            }
        }
    }

    private void classifyDuplicates(List<StatementItem> lines, List<LedgerItem> ledger, List<MatchDecision> matched,
            RuleTrace trace, List<BreakDecision> breaks) {
        for (Iterator<StatementItem> it = lines.iterator(); it.hasNext();) {
            StatementItem line = it.next();
            matched.stream()
                    .filter(m -> isCopy(line.normalizedReference(), line.amountCents(), m.line().normalizedReference(),
                            m.line().amountCents(), Dates.daysApart(line.date(), m.line().date())))
                    .min(Comparator.comparingInt(m -> m.line().rowNumber()))
                    .ifPresent(original -> {
                        breaks.add(new BreakDecision(BreakType.DUPLICATE, line.accountId(), null, line,
                                Math.abs(line.amountCents()), line.date(),
                                "Repeats statement row " + original.line().rowNumber() + ", which is matched to ledger record #"
                                        + original.ledger().id() + " (" + original.rule() + ").",
                                trace.forLine(line)));
                        it.remove();
                    });
        }
        for (Iterator<LedgerItem> it = ledger.iterator(); it.hasNext();) {
            LedgerItem record = it.next();
            matched.stream()
                    .filter(m -> isCopy(record.normalizedReference(), record.amountCents(),
                            m.ledger().normalizedReference(), m.ledger().amountCents(),
                            Dates.daysApart(record.date(), m.ledger().date())))
                    .min(Comparator.comparingLong(m -> m.ledger().id()))
                    .ifPresent(original -> {
                        breaks.add(new BreakDecision(BreakType.DUPLICATE, record.accountId(), record, null,
                                Math.abs(record.amountCents()), record.date(),
                                "Repeats ledger record #" + original.ledger().id() + ", which is matched to statement row "
                                        + original.line().rowNumber() + " (" + original.rule() + ").",
                                trace.forLedger(record)));
                        it.remove();
                    });
        }
    }

    private void classifyAmountMismatches(List<StatementItem> lines, List<LedgerItem> ledger, RuleTrace trace,
            List<BreakDecision> breaks) {
        for (Iterator<StatementItem> it = lines.iterator(); it.hasNext();) {
            StatementItem line = it.next();
            if (line.normalizedReference().isEmpty()) {
                continue;
            }
            Optional<LedgerItem> counterpart = ledger.stream()
                    .filter(l -> l.normalizedReference().equals(line.normalizedReference()))
                    .min(Comparator.comparingLong((LedgerItem l) -> Math.abs(l.amountCents() - line.amountCents()))
                            .thenComparingLong(l -> Dates.daysApart(l.date(), line.date()))
                            .thenComparingLong(LedgerItem::id));
            if (counterpart.isEmpty()) {
                continue;
            }
            LedgerItem record = counterpart.get();
            long difference = Math.abs(record.amountCents() - line.amountCents());
            breaks.add(new BreakDecision(BreakType.AMOUNT_MISMATCH, line.accountId(), record, line, difference,
                    record.date(),
                    "Ledger record #" + record.id() + " and statement row " + line.rowNumber()
                            + " have the same reference but their amounts differ by " + Money.format(difference)
                            + ", more than the " + Money.format(tolerance.amountCents()) + " tolerance.",
                    trace.forLine(line)));
            ledger.remove(record);
            it.remove();
        }
    }

    // A blank reference proves nothing, so it never makes a duplicate.
    private boolean isCopy(String reference, long amount, String otherReference, long otherAmount, long daysApart) {
        return !reference.isEmpty() && reference.equals(otherReference) && amount == otherAmount
                && daysApart <= tolerance.dateWindowDays();
    }
}
