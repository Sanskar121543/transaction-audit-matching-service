package io.github.sanskar121543.reconciliation.matching;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * The default error handler retries a batch a few times without pause and then skips it, which would
 * silently drop statement lines. Only transient failures reach this handler (StatementLineListener handles
 * the rest itself), so it retries them with back-off until they succeed, and says so at WARN: on its own
 * the container logs a failing batch only at DEBUG.
 */
@Configuration(proxyBeanMethods = false)
class ConsumerErrorHandling {

    private static final Logger log = LoggerFactory.getLogger(ConsumerErrorHandling.class);

    @Bean
    DefaultErrorHandler kafkaErrorHandler() {
        ExponentialBackOff backOff = new ExponentialBackOff(500, 2.0);
        // Capped well below max.poll.interval.ms, so a long outage does not get the consumer evicted.
        backOff.setMaxInterval(30_000);
        DefaultErrorHandler handler = new DefaultErrorHandler(backOff);
        handler.setRetryListeners(new RetryListener() {

            @Override
            public void failedDelivery(ConsumerRecord<?, ?> record, Exception ex, int deliveryAttempt) {
                log.warn("Statement line {}-{}@{} failed (attempt {}); retrying: {}", record.topic(),
                        record.partition(), record.offset(), deliveryAttempt, ex.getMessage());
            }

            @Override
            public void failedDelivery(ConsumerRecords<?, ?> records, Exception ex, int deliveryAttempt) {
                log.warn("A batch of {} statement lines failed (attempt {}); retrying: {}", records.count(),
                        deliveryAttempt, ex.getMessage());
            }
        });
        return handler;
    }
}
