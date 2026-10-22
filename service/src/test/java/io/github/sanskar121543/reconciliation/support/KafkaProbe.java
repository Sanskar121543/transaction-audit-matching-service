package io.github.sanskar121543.reconciliation.support;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.MemberDescription;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.GroupState;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;

/**
 * Looks at the statement topic and the workers' consumer group from outside, like an operator with the
 * Kafka tools: who owns which partition, how far the group has read, what is on the dead-letter topic.
 */
public final class KafkaProbe implements AutoCloseable {

    public static final String TOPIC = "statement.lines";
    public static final String DEAD_LETTER_TOPIC = "statement.lines.DLT";
    public static final String GROUP = "statement-matcher";
    private static final int PARTITIONS = 6;

    private final String bootstrapServers;
    private final Admin admin;

    public KafkaProbe(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
        this.admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers));
    }

    /** Partitions owned by each instance; consumer client ids are "{instance}-{thread}". */
    public Map<String, Set<Integer>> assignment() {
        Map<String, Set<Integer>> owned = new TreeMap<>();
        for (MemberDescription member : group().members()) {
            String instance = member.clientId().replaceFirst("-\\d+$", "");
            member.assignment().topicPartitions().stream()
                    .filter(tp -> tp.topic().equals(TOPIC))
                    .forEach(tp -> owned.computeIfAbsent(instance, i -> new TreeSet<>()).add(tp.partition()));
        }
        return owned;
    }

    /** Waits until the group is stable and exactly these instances share all six partitions. */
    public void awaitStableGroup(Set<String> instances) {
        Awaitility.await("consumer group stable across " + instances)
                .atMost(Duration.ofSeconds(90))
                .pollInterval(Duration.ofMillis(250))
                .until(() -> group().groupState() == GroupState.STABLE
                        && assignment().keySet().equals(instances)
                        && assignment().values().stream().mapToInt(Set::size).sum() == PARTITIONS);
    }

    /** Sum of the end offsets of every partition: how many records were ever published. */
    public long publishedRecords() {
        return endOffsets().values().stream().mapToLong(Long::longValue).sum();
    }

    /** Waits until the group has read every record published so far, i.e. no partition is stuck. */
    public void awaitNoLag(Duration timeout) {
        Awaitility.await("no consumer lag on " + TOPIC)
                .atMost(timeout)
                .pollInterval(Duration.ofMillis(250))
                .until(() -> committedOffsets().equals(endOffsets()));
    }

    /** Every record now on the dead-letter topic, read from the start by a throwaway consumer. */
    public List<ConsumerRecord<String, String>> deadLetters(int atLeast, Duration timeout) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "probe-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(DEAD_LETTER_TOPIC));
            Instant deadline = Instant.now().plus(timeout);
            while (records.size() < atLeast && Instant.now().isBefore(deadline)) {
                consumer.poll(Duration.ofMillis(250)).forEach(records::add);
            }
        }
        return records;
    }

    @Override
    public void close() {
        admin.close();
    }

    private ConsumerGroupDescription group() {
        return get(() -> admin.describeConsumerGroups(List.of(GROUP)).all().get().get(GROUP));
    }

    private Map<Integer, Long> endOffsets() {
        Map<TopicPartition, OffsetSpec> latest = partitions().stream()
                .collect(Collectors.toMap(Function.identity(), tp -> OffsetSpec.latest()));
        return get(() -> admin.listOffsets(latest).all().get()).entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().partition(), e -> e.getValue().offset(), (a, b) -> a,
                        TreeMap::new));
    }

    // A partition the group has never committed on counts as offset 0, which equals its end only if it is empty.
    private Map<Integer, Long> committedOffsets() {
        Map<TopicPartition, OffsetAndMetadata> committed =
                get(() -> admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get());
        Map<Integer, Long> offsets = new TreeMap<>();
        for (TopicPartition tp : partitions()) {
            OffsetAndMetadata offset = committed.get(tp);
            offsets.put(tp.partition(), offset == null ? 0L : offset.offset());
        }
        return offsets;
    }

    private static List<TopicPartition> partitions() {
        return IntStream.range(0, PARTITIONS).mapToObj(p -> new TopicPartition(TOPIC, p)).toList();
    }

    private interface KafkaCall<T> {
        T call() throws InterruptedException, ExecutionException;
    }

    private static <T> T get(KafkaCall<T> call) {
        try {
            return call.call();
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
