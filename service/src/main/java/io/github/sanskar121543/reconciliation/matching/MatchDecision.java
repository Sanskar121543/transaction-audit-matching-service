package io.github.sanskar121543.reconciliation.matching;

public record MatchDecision(LedgerItem ledger, StatementItem line, Rule rule) {
}
