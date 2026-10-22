package io.github.sanskar121543.reconciliation.matching;

import io.github.sanskar121543.reconciliation.ingest.StatementLineMessage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Decides which delivered lines of one run a worker may store now. Within an account, a line is stored only
 * once the line before it (its {@code previousRow}) is stored, so the exact rule always sees an account's
 * lines in file order, as it would reading the file top to bottom. Kafka keeps one producer's lines in order
 * on a partition, but a redelivery, a second upload of the same file, or a send that failed in the middle
 * of an upload can still put a line before its predecessor. Such a line is dropped here; it comes again
 * when the file is uploaded again.
 */
final class LineChain {

    private LineChain() {
    }

    /** {@code ready} is in row order. A line delivered twice in one batch counts once. */
    record Selection(List<StatementLineMessage> ready, int alreadyStored, int predecessorMissing) {
    }

    /** Every delivered row and every predecessor row: whether those are stored decides the selection. */
    static Set<Integer> rowsToCheck(List<StatementLineMessage> delivered) {
        Set<Integer> rows = new HashSet<>();
        for (StatementLineMessage line : delivered) {
            rows.add(line.row());
            if (line.previousRow() > 0) {
                rows.add(line.previousRow());
            }
        }
        return rows;
    }

    /** {@code stored} says which of {@link #rowsToCheck} rows are already stored. */
    static Selection select(List<StatementLineMessage> delivered, Set<Integer> stored) {
        Map<String, TreeMap<Integer, StatementLineMessage>> byAccount = delivered.stream()
                .collect(Collectors.groupingBy(StatementLineMessage::accountId, TreeMap::new,
                        Collectors.toMap(StatementLineMessage::row, line -> line, (first, again) -> first,
                                TreeMap::new)));
        List<StatementLineMessage> ready = new ArrayList<>();
        Set<Integer> readyRows = new HashSet<>();
        int alreadyStored = delivered.size() - byAccount.values().stream().mapToInt(Map::size).sum();
        int predecessorMissing = 0;
        for (TreeMap<Integer, StatementLineMessage> lines : byAccount.values()) {
            for (StatementLineMessage line : lines.values()) {
                if (stored.contains(line.row())) {
                    alreadyStored++;
                } else if (line.previousRow() == 0 || stored.contains(line.previousRow())
                        || readyRows.contains(line.previousRow())) {
                    ready.add(line);
                    readyRows.add(line.row());
                } else {
                    predecessorMissing++;
                }
            }
        }
        ready.sort(Comparator.comparingInt(StatementLineMessage::row));
        return new Selection(ready, alreadyStored, predecessorMissing);
    }
}
