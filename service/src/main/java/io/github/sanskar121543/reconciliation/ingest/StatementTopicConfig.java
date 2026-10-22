package io.github.sanskar121543.reconciliation.ingest;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration(proxyBeanMethods = false)
class StatementTopicConfig {

    // 6 partitions split evenly over 1, 2 or 3 workers. The lines do not have to: an account's lines all go to
    // the partition its id hashes to, so a busy account, or several on one partition, set the pace.
    @Bean
    NewTopic statementLinesTopic(StatementTopicProperties properties) {
        return TopicBuilder.name(properties.topic()).partitions(properties.partitions()).replicas(1).build();
    }

    // Same partition count: a dead letter keeps the partition of the record that failed.
    @Bean
    NewTopic statementLinesDeadLetterTopic(StatementTopicProperties properties) {
        return TopicBuilder.name(properties.deadLetterTopic()).partitions(properties.partitions()).replicas(1).build();
    }
}
