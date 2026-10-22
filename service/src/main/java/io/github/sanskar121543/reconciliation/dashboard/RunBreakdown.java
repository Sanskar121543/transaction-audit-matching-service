package io.github.sanskar121543.reconciliation.dashboard;

import io.github.sanskar121543.reconciliation.breaks.AgingBucket;
import io.github.sanskar121543.reconciliation.breaks.BreakType;
import java.util.List;
import java.util.Map;

/**
 * One run's breaks, counted the ways the dashboard charts them. "Unresolved" means OPEN or INVESTIGATING.
 * Ages are counted from each break's item date to today, as in the break list.
 */
public record RunBreakdown(long runId, List<TypeRow> byType, Map<String, Long> byStatus, List<AgingRow> byAge,
        Map<String, Long> resolutions, List<AccountRow> topUnresolvedAccounts) {

    public record TypeRow(BreakType type, long breaks, long exposureCents, long unresolved,
            long unresolvedExposureCents) {
    }

    public record AgingRow(AgingBucket bucket, String label, long open, long investigating, long resolved) {
    }

    /** The accounts with the most unresolved exposure, largest first. */
    public record AccountRow(String accountId, long unresolved, long unresolvedExposureCents) {
    }
}
