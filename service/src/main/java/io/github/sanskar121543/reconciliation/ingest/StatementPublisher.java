package io.github.sanskar121543.reconciliation.ingest;

import io.github.sanskar121543.reconciliation.web.CorrelationIdFilter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Queues a statement's accepted lines on Kafka and returns only once the broker has acknowledged every one.
 * Publishing the same file again is always safe: workers skip lines they have already stored.
 */
@Component
public class StatementPublisher {

    public static final String CORRELATION_ID_HEADER = "correlation-id";

    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper jsonMapper;
    private final StatementTopicProperties topic;

    public StatementPublisher(KafkaTemplate<String, String> kafka, JsonMapper jsonMapper,
            StatementTopicProperties topic) {
        this.kafka = kafka;
        this.jsonMapper = jsonMapper;
        this.topic = topic;
    }

    /**
     * {@code storedThrough} maps an account to the last row of it the workers have already stored; those lines
     * and the ones before them are not sent again. Workers store an account's lines strictly in file order,
     * so every line up to that row is stored too. Empty for a first upload.
     */
    public void publish(long runId, String sha256, List<StatementLineInput> lines, Map<String, Integer> storedThrough) {
        send(runId, messages(runId, sha256, lines).stream()
                .filter(m -> m.row() > storedThrough.getOrDefault(m.accountId(), 0))
                .toList());
    }

    /**
     * One message per line, linking each to the line before it in the same account. {@code lines} must be the
     * whole accepted file in row order (as the parser returns it), so every upload of a file links the same way.
     */
    public static List<StatementLineMessage> messages(long runId, String sha256, List<StatementLineInput> lines) {
        Map<String, Integer> lastRowByAccount = new HashMap<>();
        List<StatementLineMessage> messages = new ArrayList<>(lines.size());
        for (StatementLineInput line : lines) {
            Integer previous = lastRowByAccount.put(line.accountId(), line.row());
            messages.add(new StatementLineMessage(runId, sha256, line.row(), previous == null ? 0 : previous,
                    line.accountId(), line.valueDate(), line.amountCents(), line.reference(), line.description(),
                    line.bankTransactionId()));
        }
        return messages;
    }

    /**
     * Sends in list order, keyed by account, and waits for every acknowledgement. Only a count of the sends
     * still in flight is kept, not their futures: holding 100,000 acknowledged records until the last one
     * arrives would cost more heap than the parsed file itself.
     */
    public void send(long runId, List<StatementLineMessage> messages) {
        String correlationId = CorrelationIdFilter.current();
        AtomicInteger inFlight = new AtomicInteger(1);
        CompletableFuture<Void> allAcknowledged = new CompletableFuture<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            for (StatementLineMessage message : messages) {
                // A send that fails at once (no broker within max.block.ms) throws from send() and lands in the
                // catch below. This catches the broker rejecting an earlier batch: stop sending the rest.
                if (failure.get() != null) {
                    break;
                }
                ProducerRecord<String, String> record = new ProducerRecord<>(topic.topic(), message.accountId(),
                        jsonMapper.writeValueAsString(message));
                if (correlationId != null) {
                    record.headers().add(CORRELATION_ID_HEADER, correlationId.getBytes(StandardCharsets.UTF_8));
                }
                inFlight.incrementAndGet();
                kafka.send(record).whenComplete((result, error) -> {
                    if (error != null) {
                        failure.compareAndSet(null, error);
                    }
                    if (inFlight.decrementAndGet() == 0) {
                        allAcknowledged.complete(null);
                    }
                });
            }
            if (inFlight.decrementAndGet() == 0) {
                allAcknowledged.complete(null);
            }
            allAcknowledged.get(topic.publishTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            throw new StatementNotQueuedException(runId, failure.get() != null ? failure.get() : e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StatementNotQueuedException(runId, e);
        }
        if (failure.get() != null) {
            throw new StatementNotQueuedException(runId, failure.get());
        }
    }
}
