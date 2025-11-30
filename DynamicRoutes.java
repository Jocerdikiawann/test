package com.company.queue.registry;

import com.company.queue.model.TaskPayload;
import com.company.queue.service.TaskExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

public class DynamicConsumer {

    private static final Logger log = LoggerFactory.getLogger(DynamicConsumer.class);

    private final HandlerMetadata metadata;
    private final KafkaConsumer<String, String> consumer;
    private final ExecutorService executorService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final TaskExecutor taskExecutor;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    public DynamicConsumer(
        HandlerMetadata metadata,
        String bootstrapServers,
        ExecutorService executorService,
        TaskExecutor taskExecutor,
        MeterRegistry meterRegistry,
        ObjectMapper objectMapper
    ) {
        this.metadata = metadata;
        this.executorService = executorService;
        this.taskExecutor = taskExecutor;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
        this.consumer = createConsumer(metadata, bootstrapServers);
    }

    private KafkaConsumer<String, String> createConsumer(
        HandlerMetadata metadata,
        String bootstrapServers
    ) {
        Properties props = new Properties();

        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG,
            "queue-service-" + metadata.getRoutingKey());
        props.put(ConsumerConfig.CLIENT_ID_CONFIG,
            "queue-service-" + metadata.getRoutingKey() + "-" + System.currentTimeMillis());

        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class.getName());

        // Handler-specific config
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,
            metadata.getMaxPollRecords());
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG,
            metadata.getMaxPollIntervalMs());
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG,
            metadata.getSessionTimeoutMs());
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 3000);

        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");

        // Performance tuning
        props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1);
        props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 500);

        return new KafkaConsumer<>(props);
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            consumer.subscribe(Collections.singletonList(metadata.getTopicName()));

            // Start consuming in thread pool
            for (int i = 0; i < metadata.getConcurrency(); i++) {
                final int consumerId = i;
                executorService.submit(() -> consumeLoop(consumerId));
            }

            log.info("✅ Consumer started: routing={}, topic={}, concurrency={}",
                metadata.getRoutingKey(),
                metadata.getTopicName(),
                metadata.getConcurrency()
            );
        }
    }

    private void consumeLoop(int consumerId) {
        String threadName = String.format("consumer-%s-%d", metadata.getRoutingKey(), consumerId);
        Thread.currentThread().setName(threadName);

        log.info("🔄 Consumer thread started: {}", threadName);

        try {
            while (running.get()) {
                try {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(1));

                    if (!records.isEmpty()) {
                        log.debug("📦 Polled {} records from topic: {} (consumer: {})",
                            records.count(),
                            metadata.getTopicName(),
                            consumerId
                        );

                        for (ConsumerRecord<String, String> record : records) {
                            processRecord(record, consumerId);
                        }

                        // Commit offset after processing all records
                        consumer.commitSync();
                    }

                } catch (Exception e) {
                    log.error("Error polling records (consumer: {}): {}", consumerId, e.getMessage());

                    // Sleep before retry
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } finally {
            log.info("🛑 Consumer thread stopped: {}", threadName);
        }
    }

    private void processRecord(ConsumerRecord<String, String> record, int consumerId) {
        Instant startTime = Instant.now();
        Timer.Sample sample = Timer.start(meterRegistry);

        TaskPayload task = null;

        try {
            // Parse payload
            task = objectMapper.readValue(record.value(), TaskPayload.class);

            log.info("▶️  Processing task: id={}, routing={}, partition={}, offset={}, consumer={}",
                task.getTaskId(),
                metadata.getRoutingKey(),
                record.partition(),
                record.offset(),
                consumerId
            );

            // Execute handler
            taskExecutor.execute(task, metadata).toCompletableFuture().get();

            // Success metrics
            Duration duration = Duration.between(startTime, Instant.now());
            sample.stop(Timer.builder("task.execution.time")
                .tag("routing", metadata.getRoutingKey())
                .tag("status", "success")
                .register(meterRegistry));

            meterRegistry.counter("task.completed",
                "routing", metadata.getRoutingKey()
            ).increment();

            log.info("✅ Task completed: id={}, routing={}, duration={}ms, consumer={}",
                task.getTaskId(),
                metadata.getRoutingKey(),
                duration.toMillis(),
                consumerId
            );

        } catch (Exception e) {
            // Failure metrics
            sample.stop(Timer.builder("task.execution.time")
                .tag("routing", metadata.getRoutingKey())
                .tag("status", "failure")
                .register(meterRegistry));

            meterRegistry.counter("task.failed",
                "routing", metadata.getRoutingKey(),
                "error", e.getClass().getSimpleName()
            ).increment();

            log.error("❌ Task failed: id={}, routing={}, partition={}, offset={}, consumer={}",
                task != null ? task.getTaskId() : "unknown",
                metadata.getRoutingKey(),
                record.partition(),
                record.offset(),
                consumerId,
                e
            );

            // Handle retry/DLQ
            if (task != null) {
                try {
                    task.recordError(e);

                    if (task.getRetryCount() < metadata.getMaxRetries()) {
                        taskExecutor.sendToRetry(task, metadata).toCompletableFuture().get();
                    } else {
                        taskExecutor.sendToDLQ(task, metadata, e).toCompletableFuture().get();
                    }
                } catch (Exception retryException) {
                    log.error("Failed to handle retry/DLQ for task: {}", task.getTaskId(), retryException);
                }
            }
        }
    }

    public void stop() {
        if (running.compareAndSet(true, false)) {
            try {
                consumer.wakeup();
                consumer.close(Duration.ofSeconds(30));
                log.info("🛑 Consumer stopped: routing={}, topic={}",
                    metadata.getRoutingKey(),
                    metadata.getTopicName()
                );
            } catch (Exception e) {
                log.error("Error stopping consumer: {}", metadata.getRoutingKey(), e);
            }
        }
    }

    public HandlerMetadata getMetadata() {
        return metadata;
    }

    public boolean isRunning() {
        return running.get();
    }
}
