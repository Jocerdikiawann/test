package com.company.queue.annotation;

import java.lang.annotation.*;

/**
 * Annotation untuk mendefinisikan Queue Handler
 * Handler akan otomatis:
 * - Membuat Kafka topic
 * - Register consumer
 * - Setup retry & DLQ
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface QueueHandler {

    /**
     * Routing key (akan jadi topic name)
     * Contoh: "document.generate" → "document-generate-tasks"
     */
    String value();

    /**
     * Custom topic name (optional)
     * Kalau kosong, auto-generate dari routing key
     */
    String topic() default "";

    /**
     * Description untuk documentation
     */
    String description() default "";

    /**
     * Jumlah concurrent consumers
     * Untuk heavy task: 1-5
     * Untuk light task: 10-50
     */
    int concurrency() default 10;

    /**
     * Max records per poll
     * Untuk heavy task: 1-5
     * Untuk light task: 50-100
     */
    int maxPollRecords() default 10;

    /**
     * Max poll interval (ms)
     * Default: 5 menit
     * Heavy task: 10-15 menit
     */
    int maxPollIntervalMs() default 300000;

    /**
     * Session timeout (ms)
     */
    int sessionTimeoutMs() default 60000;

    /**
     * Timeout per task execution (seconds)
     */
    int timeout() default 300;

    /**
     * Max retry attempts
     */
    int maxRetries() default 3;

    /**
     * Retry delay multiplier (seconds)
     * Exponential backoff: delay * 2^retryCount
     */
    int retryDelayMultiplier() default 5;

    /**
     * Number of partitions for topic
     */
    int partitions() default 10;

    /**
     * Replication factor
     */
    short replicationFactor() default 3;

    /**
     * Enable DLQ
     */
    boolean enableDLQ() default true;

    /**
     * Priority (higher = more important)
     */
    int priority() default 0;
}
