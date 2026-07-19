package com.doob.mathagent.teacher.sync.mq;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Broker topology and bounded worker settings for teacher source synchronization.
 *
 * <p>Keeping names and concurrency in configuration makes multi-environment deployment explicit and avoids hidden
 * broker defaults in Java code.</p>
 */
@ConfigurationProperties(prefix = "math-agent.teacher.sync.rabbitmq")
public record TeacherSourceSyncRabbitProperties(
        String exchange,
        String routingKey,
        String queue,
        String deadLetterQueue,
        int consumerConcurrency,
        int consumerMaxConcurrency,
        int prefetch,
        long publisherConfirmTimeoutMilliseconds) {

    private static final int MINIMUM_CONCURRENCY = 1;

    public TeacherSourceSyncRabbitProperties {
        exchange = required(exchange, "teacher.source-sync");
        routingKey = required(routingKey, "teacher.source-sync.execute");
        queue = required(queue, "teacher.source-sync.execute.q");
        deadLetterQueue = required(deadLetterQueue, "teacher.source-sync.execute.dlq");
        consumerConcurrency = Math.max(MINIMUM_CONCURRENCY, consumerConcurrency);
        consumerMaxConcurrency = Math.max(consumerConcurrency, consumerMaxConcurrency);
        prefetch = Math.max(MINIMUM_CONCURRENCY, prefetch);
        publisherConfirmTimeoutMilliseconds = Math.max(MINIMUM_CONCURRENCY, publisherConfirmTimeoutMilliseconds);
    }

    private static String required(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
