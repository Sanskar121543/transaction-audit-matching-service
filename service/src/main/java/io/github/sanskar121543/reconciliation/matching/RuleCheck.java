package io.github.sanskar121543.reconciliation.matching;

/**
 * One line of "why didn't this match": what a rule found when it was tried for an unmatched record.
 * Built only from ids, numbers and fixed wording, never from imported text, so it is safe to show as is.
 */
public record RuleCheck(Rule rule, Result result, Long ledgerRecordId, Long statementLineId,
        Long amountDifferenceCents, Long dateDifferenceDays, String detail) {

    public enum Result {
        NO_REFERENCE,
        NO_CANDIDATE,
        CANDIDATE_ALREADY_MATCHED,
        AMOUNT_DIFFERS,
        SIGN_DIFFERS,
        OUTSIDE_TOLERANCE,
        OUTSIDE_DATE_WINDOW
    }
}
