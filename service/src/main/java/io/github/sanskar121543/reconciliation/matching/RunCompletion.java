package io.github.sanskar121543.reconciliation.matching;

import io.github.sanskar121543.reconciliation.common.InstanceInfo;
import io.github.sanskar121543.reconciliation.run.Run;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Ends a run once every statement line has been processed: everything still unmatched becomes a break. */
@Component
public class RunCompletion {

    private final MatchStore store;
    private final InstanceInfo instance;
    private final Clock clock;

    public RunCompletion(MatchStore store, InstanceInfo instance, Clock clock) {
        this.store = store;
        this.instance = instance;
        this.clock = clock;
    }

    public record Summary(int matches, int breaks) {
    }

    /** The caller holds the run's row lock, so no line can be added while this runs. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Summary complete(Run run) {
        long runId = run.getId();
        List<LedgerItem> ledger = store.unmatchedLedger(runId);
        List<StatementItem> lines = store.unmatchedLines(runId);
        // Every existing match that shares a reference, raw or normalised, with anything still unmatched.
        Set<String> normalized = new TreeSet<>();
        Set<String> rawWithoutLettersOrDigits = new TreeSet<>();
        Stream.concat(ledger.stream().map(l -> new String[] {l.reference(), l.normalizedReference()}),
                        lines.stream().map(s -> new String[] {s.reference(), s.normalizedReference()}))
                .forEach(reference -> {
                    if (!reference[1].isEmpty()) {
                        normalized.add(reference[1]);
                    } else if (!reference[0].isEmpty()) {
                        rawWithoutLettersOrDigits.add(reference[0]);
                    }
                });
        List<MatchDecision> related = store.matchesSharingReferences(runId, normalized, rawWithoutLettersOrDigits);

        Map<String, List<LedgerItem>> ledgerByAccount = group(ledger, LedgerItem::accountId);
        Map<String, List<StatementItem>> linesByAccount = group(lines, StatementItem::accountId);
        Map<String, List<MatchDecision>> relatedByAccount = group(related, m -> m.line().accountId());
        Set<String> accounts = new TreeSet<>(ledgerByAccount.keySet());
        accounts.addAll(linesByAccount.keySet());

        Reconciler reconciler = new Reconciler(run.tolerance());
        List<MatchDecision> matches = new ArrayList<>();
        List<BreakDecision> breaks = new ArrayList<>();
        for (String account : accounts) {
            Reconciler.Outcome outcome = reconciler.reconcile(new AccountBook(account,
                    ledgerByAccount.getOrDefault(account, List.of()), linesByAccount.getOrDefault(account, List.of()),
                    relatedByAccount.getOrDefault(account, List.of())));
            matches.addAll(outcome.matches());
            breaks.addAll(outcome.breaks());
        }
        store.insertMatches(runId, matches, instance.id());
        store.insertBreaks(runId, breaks);
        store.clearMatchingProblem(runId);
        run.complete(clock.instant());
        return new Summary(matches.size(), breaks.size());
    }

    private static <T> Map<String, List<T>> group(List<T> items, Function<T, String> account) {
        return items.stream().collect(Collectors.groupingBy(account));
    }
}
