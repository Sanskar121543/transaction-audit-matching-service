package io.github.sanskar121543.reconciliation.matching;

import io.github.sanskar121543.reconciliation.ingest.StatementLineMessage;
import io.github.sanskar121543.reconciliation.ingest.StatementPublisher;
import io.github.sanskar121543.reconciliation.ingest.StatementTopicProperties;
import io.github.sanskar121543.reconciliation.web.CorrelationIdFilter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Takes statement lines off Kafka a batch at a time, on every instance (one consumer group). Offsets are
 * committed only after this returns, so a crash means redelivery, never loss; redelivered lines are skipped.
 * <p>
 * What happens on failure:
 * <ul>
 * <li>a record that is not a valid statement line is dead-lettered: no retry could ever store it;</li>
 * <li>a transient failure (database unreachable, lock timeout, deadlock) fails the whole batch, which
 * ConsumerErrorHandling retries with back-off until it succeeds;</li>
 * <li>any other failure is tried twice more, then logged and recorded on the run (its matchingProblem), and
 * the batch goes on with the other runs. The run's lines are not stored, so it cannot complete with a line
 * missing: it stays MATCHING until the same statement is uploaded again. One run's problem never stops the
 * partition for everybody else's.</li>
 * </ul>
 */
@Component
class StatementLineListener {

    static final String LISTENER_ID = "statement-lines";
    private static final int ATTEMPTS = 3;

    private static final Logger log = LoggerFactory.getLogger(StatementLineListener.class);

    private final StatementLineWorker worker;
    private final MatchStore store;
    private final KafkaTemplate<String, String> kafka;
    private final StatementTopicProperties topic;
    private final JsonMapper lineReader;

    StatementLineListener(StatementLineWorker worker, MatchStore store, KafkaTemplate<String, String> kafka,
            StatementTopicProperties topic, JsonMapper jsonMapper) {
        this.worker = worker;
        this.store = store;
        this.kafka = kafka;
        this.topic = topic;
        // Tolerant of fields it does not know (a newer instance may add some during a rolling deploy), strict
        // about missing ones: a missing previousRow must not read as 0, "first line of the account".
        this.lineReader = jsonMapper.rebuild()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .build();
    }

    @KafkaListener(id = LISTENER_ID, topics = "${matching.kafka.topic}", groupId = "${spring.kafka.consumer.group-id}",
            batch = "true")
    void onLines(List<ConsumerRecord<String, String>> records) {
        Map<Long, List<StatementLineMessage>> byRun = new TreeMap<>();
        Map<Long, String> correlationIds = new TreeMap<>();
        for (ConsumerRecord<String, String> record : records) {
            Optional<StatementLineMessage> line = read(record);
            if (line.isEmpty()) {
                continue;
            }
            byRun.computeIfAbsent(line.get().runId(), id -> new ArrayList<>()).add(line.get());
            Header correlation = record.headers().lastHeader(StatementPublisher.CORRELATION_ID_HEADER);
            if (correlation != null) {
                correlationIds.putIfAbsent(line.get().runId(), new String(correlation.value(), StandardCharsets.UTF_8));
            }
        }
        for (Map.Entry<Long, List<StatementLineMessage>> run : byRun.entrySet()) {
            String correlationId = correlationIds.get(run.getKey());
            if (correlationId != null) {
                MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
            }
            try {
                handle(run.getKey(), run.getValue());
            } finally {
                MDC.remove(CorrelationIdFilter.MDC_KEY);
            }
        }
    }

    // A failure that is not transient gets a few quick attempts anyway, in case it was transient after all.
    private void handle(long runId, List<StatementLineMessage> lines) {
        for (int attempt = 1; ; attempt++) {
            try {
                worker.handle(runId, lines);
                return;
            } catch (RuntimeException e) {
                if (TransientFailures.isTransient(e)) {
                    throw e;
                }
                if (attempt == ATTEMPTS) {
                    log.error("Run {}: {} delivered lines could not be stored and were skipped. The run stays MATCHING; "
                            + "upload the same statement again once the cause is fixed", runId, lines.size(), e);
                    store.recordMatchingProblem(runId, TransientFailures.describe(e));
                    return;
                }
                log.warn("Run {}: storing {} lines failed (attempt {} of {}); trying again: {}", runId, lines.size(),
                        attempt, ATTEMPTS, e.toString());
                pause(Duration.ofMillis(250L * attempt));
            }
        }
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private Optional<StatementLineMessage> read(ConsumerRecord<String, String> record) {
        String problem;
        try {
            StatementLineMessage line = lineReader.readValue(record.value(), StatementLineMessage.class);
            Optional<String> invalid = line.invalidBecause();
            if (invalid.isEmpty()) {
                return Optional.of(line);
            }
            problem = invalid.get();
        } catch (JacksonException | IllegalArgumentException e) {
            problem = e.getMessage();
        }
        deadLetter(record, problem);
        return Optional.empty();
    }

    // Synchronous, so the offset is not committed past a record that never reached the dead-letter topic.
    private void deadLetter(ConsumerRecord<String, String> record, String problem) {
        log.error("Record {}-{}@{} is not a valid statement line; sent to {}: {}", record.topic(), record.partition(),
                record.offset(), topic.deadLetterTopic(), problem);
        // A copy: the producer seals the headers it sends, and a retried batch would dead-letter this record again.
        RecordHeaders headers = new RecordHeaders(record.headers().toArray());
        headers.add("dead-letter-reason", String.valueOf(problem).getBytes(StandardCharsets.UTF_8));
        ProducerRecord<String, String> dead = new ProducerRecord<>(topic.deadLetterTopic(), record.partition(),
                record.key(), record.value(), headers);
        try {
            kafka.send(dead).get(30, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Could not dead-letter " + record.topic() + "-" + record.partition()
                    + "@" + record.offset(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
