package io.github.sanskar121543.reconciliation.matching;

import io.github.sanskar121543.reconciliation.breaks.BreakType;
import java.time.LocalDate;
import java.util.List;

/** A break as decided at the end of a run; {@code ledger} or {@code line} is null for one-sided breaks. */
public record BreakDecision(BreakType type, String accountId, LedgerItem ledger, StatementItem line,
        long exposureCents, LocalDate itemDate, String verdict, List<RuleCheck> trace) {
}
