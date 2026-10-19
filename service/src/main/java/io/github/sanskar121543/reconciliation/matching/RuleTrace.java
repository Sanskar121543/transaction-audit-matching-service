package io.github.sanskar121543.reconciliation.matching;

import io.github.sanskar121543.reconciliation.common.Money;
import io.github.sanskar121543.reconciliation.matching.RuleCheck.Result;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Explains, rule by rule, why an unmatched record did not match: what each rule looked for and the
 * closest thing it found. It looks at the account as it stood when matching finished. The text is built
 * from ids, numbers and fixed wording only, never from imported data.
 */
final class RuleTrace {

    private final Tolerance tolerance;
    private final List<LedgerItem> unmatchedLedger;
    private final List<StatementItem> unmatchedLines;
    private final List<MatchDecision> matched;

    RuleTrace(Tolerance tolerance, List<LedgerItem> unmatchedLedger, List<StatementItem> unmatchedLines,
            List<MatchDecision> matched) {
        this.tolerance = tolerance;
        this.unmatchedLedger = unmatchedLedger;
        this.unmatchedLines = unmatchedLines;
        this.matched = matched;
    }

    /**
     * A record on the other side of the account, whichever side that is. {@code order} (ledger id or
     * statement row) breaks ties. {@code matchedTo} names its partner if it is already matched, and is null
     * if it is still free.
     */
    private record Candidate(Long ledgerRecordId, Long statementLineId, long order, String name, long amountCents,
            LocalDate date, String reference, String normalized, String matchedTo) {

        boolean free() {
            return matchedTo == null;
        }
    }

    List<RuleCheck> forLine(StatementItem line) {
        List<Candidate> ledger = Stream.concat(
                unmatchedLedger.stream().map(l -> candidate(l, null)),
                matched.stream().map(m -> candidate(m.ledger(), "statement row " + m.line().rowNumber())))
                .toList();
        return explain("bank line", "ledger record", line.amountCents(), line.date(), line.reference(),
                line.normalizedReference(), ledger);
    }

    List<RuleCheck> forLedger(LedgerItem record) {
        List<Candidate> lines = Stream.concat(
                unmatchedLines.stream().map(s -> candidate(s, null)),
                matched.stream().map(m -> candidate(m.line(), "ledger record #" + m.ledger().id())))
                .toList();
        return explain("ledger record", "bank line", record.amountCents(), record.date(), record.reference(),
                record.normalizedReference(), lines);
    }

    private List<RuleCheck> explain(String noun, String otherNoun, long amount, LocalDate date, String reference,
            String normalized, List<Candidate> others) {
        return List.of(exact(noun, otherNoun, amount, reference, others),
                tolerance(otherNoun, amount, date, others),
                normalized(noun, otherNoun, amount, date, normalized, others));
    }

    private RuleCheck exact(String noun, String otherNoun, long amount, String reference, List<Candidate> others) {
        if (reference.isEmpty()) {
            return check(Rule.EXACT, Result.NO_REFERENCE, null, null, null, "The " + noun + " has no reference.");
        }
        List<Candidate> sameReference = others.stream().filter(c -> c.reference().equals(reference)).toList();
        Optional<Candidate> taken = sameReference.stream()
                .filter(c -> c.amountCents() == amount && !c.free())
                .min(Comparator.comparingLong(Candidate::order));
        if (taken.isPresent()) {
            return check(Rule.EXACT, Result.CANDIDATE_ALREADY_MATCHED, taken.get(), 0L, null, taken.get().name()
                    + " has the same reference and amount, but it is already matched to " + taken.get().matchedTo() + ".");
        }
        Optional<Candidate> closest = sameReference.stream().min(byAmountFrom(amount));
        if (closest.isEmpty()) {
            return check(Rule.EXACT, Result.NO_CANDIDATE, null, null, null,
                    "No " + otherNoun + " in this account has the same reference.");
        }
        long difference = closest.get().amountCents() - amount;
        return check(Rule.EXACT, Result.AMOUNT_DIFFERS, closest.get(), difference, null, closest.get().name()
                + " has the same reference, but its amount differs by " + Money.format(Math.abs(difference)) + ".");
    }

    private RuleCheck tolerance(String otherNoun, long amount, LocalDate date, List<Candidate> others) {
        List<Candidate> sameSign = others.stream()
                .filter(c -> c.free() && Long.signum(c.amountCents()) == Long.signum(amount))
                .toList();
        if (sameSign.isEmpty()) {
            return check(Rule.TOLERANCE, Result.NO_CANDIDATE, null, null, null,
                    "No unmatched " + otherNoun + " in this account has the same sign.");
        }
        List<Candidate> withinAmount = sameSign.stream()
                .filter(c -> Math.abs(c.amountCents() - amount) <= tolerance.amountCents())
                .toList();
        if (withinAmount.isEmpty()) {
            Candidate closest = sameSign.stream().min(byAmountFrom(amount)).orElseThrow();
            long difference = closest.amountCents() - amount;
            return check(Rule.TOLERANCE, Result.OUTSIDE_TOLERANCE, closest, difference,
                    Dates.daysApart(closest.date(), date), "The closest unmatched amount is " + closest.name()
                            + ", which differs by " + Money.format(Math.abs(difference)) + " (tolerance "
                            + Money.format(tolerance.amountCents()) + ").");
        }
        Candidate closest = withinAmount.stream()
                .min(Comparator.comparingLong((Candidate c) -> Dates.daysApart(c.date(), date))
                        .thenComparingLong(Candidate::order))
                .orElseThrow();
        long days = Dates.daysApart(closest.date(), date);
        return check(Rule.TOLERANCE, Result.OUTSIDE_DATE_WINDOW, closest, closest.amountCents() - amount, days,
                closest.name() + " is within the amount tolerance, but " + days + " days apart (window "
                        + tolerance.dateWindowDays() + " days).");
    }

    private RuleCheck normalized(String noun, String otherNoun, long amount, LocalDate date, String normalized,
            List<Candidate> others) {
        if (normalized.isEmpty()) {
            return check(Rule.NORMALIZED_REFERENCE, Result.NO_REFERENCE, null, null, null,
                    "The " + noun + "'s reference has no letters or digits.");
        }
        List<Candidate> sameReference = others.stream().filter(c -> c.normalized().equals(normalized)).toList();
        Optional<Candidate> closestFree = sameReference.stream().filter(Candidate::free).min(byAmountFrom(amount));
        if (closestFree.isEmpty()) {
            Optional<Candidate> taken = sameReference.stream().min(byAmountFrom(amount));
            if (taken.isPresent()) {
                return check(Rule.NORMALIZED_REFERENCE, Result.CANDIDATE_ALREADY_MATCHED, taken.get(),
                        taken.get().amountCents() - amount, null, taken.get().name()
                                + " has the same reference once normalised, but it is already matched to "
                                + taken.get().matchedTo() + ".");
            }
            return check(Rule.NORMALIZED_REFERENCE, Result.NO_CANDIDATE, null, null, null,
                    "No " + otherNoun + " in this account has the same reference once normalised.");
        }
        Candidate closest = closestFree.get();
        long difference = closest.amountCents() - amount;
        long days = Dates.daysApart(closest.date(), date);
        if (Long.signum(closest.amountCents()) != Long.signum(amount)) {
            return check(Rule.NORMALIZED_REFERENCE, Result.SIGN_DIFFERS, closest, difference, days,
                    closest.name() + " has the same reference once normalised, but the opposite sign.");
        }
        return check(Rule.NORMALIZED_REFERENCE, Result.OUTSIDE_TOLERANCE, closest, difference, days, closest.name()
                + " has the same reference once normalised, but its amount differs by "
                + Money.format(Math.abs(difference)) + " (tolerance " + Money.format(tolerance.amountCents()) + ").");
    }

    private static Comparator<Candidate> byAmountFrom(long amount) {
        return Comparator.comparingLong((Candidate c) -> Math.abs(c.amountCents() - amount))
                .thenComparingLong(Candidate::order);
    }

    private static Candidate candidate(LedgerItem l, String matchedTo) {
        return new Candidate(l.id(), null, l.id(), "Ledger record #" + l.id(), l.amountCents(), l.date(),
                l.reference(), l.normalizedReference(), matchedTo);
    }

    private static Candidate candidate(StatementItem s, String matchedTo) {
        return new Candidate(null, s.id(), s.rowNumber(), "Statement row " + s.rowNumber(), s.amountCents(),
                s.date(), s.reference(), s.normalizedReference(), matchedTo);
    }

    private static RuleCheck check(Rule rule, Result result, Candidate candidate, Long amountDifference,
            Long dateDifference, String detail) {
        return new RuleCheck(rule, result, candidate == null ? null : candidate.ledgerRecordId(),
                candidate == null ? null : candidate.statementLineId(), amountDifference, dateDifference, detail);
    }
}
