package io.github.sanskar121543.reconciliation.matching;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanskar121543.reconciliation.ingest.StatementLineInput;
import io.github.sanskar121543.reconciliation.ingest.StatementLineMessage;
import io.github.sanskar121543.reconciliation.ingest.StatementPublisher;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What Kafka can do to one partition, generated at random from fixed seeds: a first upload that loses some
 * sends, a second upload of the same file interleaved with it, batches cut anywhere, and batches delivered
 * again (a worker that crashed before committing its offset). Whatever happens, each account must end up
 * stored exactly once and in file order.
 */
class LineChainPropertyTest {

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20})
    void everyAccountIsStoredOnceAndInFileOrder(long seed) {
        Random random = new Random(seed);
        List<StatementLineInput> file = new ArrayList<>();
        int rows = 20 + random.nextInt(200);
        for (int row = 1; row <= rows; row++) {
            file.add(new StatementLineInput(row, "ACC-" + random.nextInt(4), LocalDate.of(2026, 10, 1), 100, "", "",
                    null));
        }
        List<StatementLineMessage> all = StatementPublisher.messages(1, "sha", file);
        double lossRate = random.nextDouble() * 0.5;
        List<StatementLineMessage> lossy = all.stream().filter(m -> random.nextDouble() >= lossRate).toList();
        List<StatementLineMessage> partition = interleave(lossy, all, random);

        Set<Integer> stored = new HashSet<>();
        Map<String, List<Integer>> storedInOrder = new TreeMap<>();
        Deque<List<StatementLineMessage>> delivered = new ArrayDeque<>();
        for (int from = 0; from < partition.size();) {
            int to = Math.min(partition.size(), from + 1 + random.nextInt(25));
            List<StatementLineMessage> batch = partition.subList(from, to);
            process(batch, stored, storedInOrder);
            delivered.push(batch);
            if (random.nextInt(5) == 0) {
                process(delivered.peek(), stored, storedInOrder);
            }
            from = to;
        }

        Map<String, List<Integer>> fileOrder = file.stream().collect(Collectors.groupingBy(StatementLineInput::accountId,
                TreeMap::new, Collectors.mapping(StatementLineInput::row, Collectors.toList())));
        assertThat(storedInOrder).as("seed %d", seed).isEqualTo(fileOrder);
    }

    private static void process(List<StatementLineMessage> batch, Set<Integer> stored,
            Map<String, List<Integer>> storedInOrder) {
        Set<Integer> known = new HashSet<>(LineChain.rowsToCheck(batch));
        known.retainAll(stored);
        for (StatementLineMessage line : LineChain.select(batch, known).ready()) {
            assertThat(stored.add(line.row())).as("row %d stored twice", line.row()).isTrue();
            storedInOrder.computeIfAbsent(line.accountId(), a -> new ArrayList<>()).add(line.row());
        }
    }

    // Two producers on one partition: each one's records stay in its own order, interleaved at random.
    private static List<StatementLineMessage> interleave(List<StatementLineMessage> first,
            List<StatementLineMessage> second, Random random) {
        List<StatementLineMessage> merged = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < first.size() || j < second.size()) {
            if (j == second.size() || (i < first.size() && random.nextBoolean())) {
                merged.add(first.get(i++));
            } else {
                merged.add(second.get(j++));
            }
        }
        return merged;
    }
}
