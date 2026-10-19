package io.github.sanskar121543.reconciliation.matching;

import io.github.sanskar121543.reconciliation.common.InstanceInfo;
import io.github.sanskar121543.reconciliation.ingest.StatementLineInput;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Stores statement lines and matches each new one by the exact rule, in row order. */
@Component
public class StatementProcessor {

    private final MatchStore store;
    private final InstanceInfo instance;

    public StatementProcessor(MatchStore store, InstanceInfo instance) {
        this.store = store;
        this.instance = instance;
    }

    /** Returns the lines that were new; lines already stored are skipped, so processing twice is harmless. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<StatementItem> process(long runId, List<StatementLineInput> lines) {
        List<StatementItem> fresh = store.insertLines(runId, lines, instance.id());
        Map<String, List<StatementItem>> byAccount = fresh.stream()
                .collect(Collectors.groupingBy(StatementItem::accountId, TreeMap::new, Collectors.toList()));
        List<MatchDecision> matches = new ArrayList<>();
        for (Map.Entry<String, List<StatementItem>> account : byAccount.entrySet()) {
            Set<String> references = account.getValue().stream()
                    .map(StatementItem::reference)
                    .filter(r -> !r.isEmpty())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (references.isEmpty()) {
                continue;
            }
            List<LedgerItem> candidates = new ArrayList<>(
                    store.unmatchedLedgerWithReferences(runId, account.getKey(), references));
            for (StatementItem line : account.getValue()) {
                ExactRule.pick(line, candidates).ifPresent(ledger -> {
                    matches.add(new MatchDecision(ledger, line, Rule.EXACT));
                    candidates.remove(ledger);
                });
            }
        }
        store.insertMatches(runId, matches, instance.id());
        return fresh;
    }
}
