package io.github.sanskar121543.reconciliation.matching;

import java.time.Instant;

public record MatchView(long id, String accountId, Rule rule, long ledgerRecordId, String ledgerExternalId,
        long statementLineId, int statementRowNumber, String matchedBy, Instant createdAt) {
}
