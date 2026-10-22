package io.github.sanskar121543.reconciliation.ingest;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The topic statement lines travel on. {@code publishTimeout} bounds how long an upload waits for Kafka to
 * acknowledge every line before it answers 503.
 */
@ConfigurationProperties("matching.kafka")
public record StatementTopicProperties(
        @DefaultValue("statement.lines") String topic,
        @DefaultValue("6") int partitions,
        @DefaultValue("60s") Duration publishTimeout) {

    public String deadLetterTopic() {
        return topic + ".DLT";
    }
}
