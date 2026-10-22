package io.github.sanskar121543.reconciliation.matching;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * One account's unmatched ledger records, indexed by amount and by normalised reference, for the end-of-run
 * rules. The rules still apply all of their own filters and tie-breaks to what this returns; it only leaves
 * out records those filters would reject anyway, so the rules pick exactly what they would from the whole
 * list, without scanning every record for every line.
 */
final class UnmatchedLedger {

    private final NavigableMap<Long, List<LedgerItem>> byAmount = new TreeMap<>();
    private final Map<String, List<LedgerItem>> byNormalizedReference = new HashMap<>();

    UnmatchedLedger(Collection<LedgerItem> records) {
        for (LedgerItem record : records) {
            byAmount.computeIfAbsent(record.amountCents(), a -> new ArrayList<>()).add(record);
            byNormalizedReference.computeIfAbsent(record.normalizedReference(), r -> new ArrayList<>()).add(record);
        }
    }

    /** Every record whose amount is within {@code tolerance} of {@code amount}. */
    List<LedgerItem> withinAmount(long amount, long tolerance) {
        List<LedgerItem> records = new ArrayList<>();
        byAmount.subMap(amount - tolerance, true, amount + tolerance, true).values().forEach(records::addAll);
        return records;
    }

    List<LedgerItem> withNormalizedReference(String normalizedReference) {
        return byNormalizedReference.getOrDefault(normalizedReference, List.of());
    }

    void remove(LedgerItem record) {
        removeFrom(byAmount, record.amountCents(), record);
        removeFrom(byNormalizedReference, record.normalizedReference(), record);
    }

    private static <K> void removeFrom(Map<K, List<LedgerItem>> index, K key, LedgerItem record) {
        List<LedgerItem> records = index.get(key);
        records.remove(record);
        if (records.isEmpty()) {
            index.remove(key);
        }
    }
}
