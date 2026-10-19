package io.github.sanskar121543.reconciliation.matching;

import java.util.List;

/**
 * Everything the end-of-run step needs for one account: what is still unmatched after the per-line
 * exact rule, and the existing matches that share a reference with any of it (to spot duplicates and to
 * explain why a record did not match).
 */
public record AccountBook(String accountId, List<LedgerItem> unmatchedLedger, List<StatementItem> unmatchedLines,
        List<MatchDecision> relatedMatches) {
}
