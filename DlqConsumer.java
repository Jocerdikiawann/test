package com.company.queue.consumer;

import com.company.queue.model.TaskPayload;
import com.company.queue.registry.DynamicConsumerRegistry;
import com.company.queue.registry.HandlerMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@ApplicationScoped
public class DLQConsumer {

    private static final Logger log = LoggerFactory.getLogger(DLQConsumer.class);

    @Inject
    DynamicConsumerRegistry registry;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    MeterRegistry meterRegistry;

    @ConfigProperty(name = "kafka.bootstrap.servers")
    String bootstrapServers;

    @ConfigProperty(name = "queue.dlq.enabled", defaultValue = "true")
    boolean enabled;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService executorService;

    void onShutdown(@Observes ShutdownEvent event) {
        stop();
    }

    public void start() {
        if (!enabled) {
            log.info("DLQ Consumer disabled");
            return;
        }

        if (running.compareAndSet(false, true)) {
            int handlerCount = registry.getAllHandlers().size();

            if (handlerCount == 0) {
                log.warn("No handlers found, DLQ consumer not started");
                return;
            }

            int threadPoolSize = Math.max(1, Math.min(handlerCount, 10));

            executorService = Executors.newFixedThreadPool(threadPoolSize);

            registry.getAllHandlers().values().forEach(metadata -> {
                if (metadata.isEnableDLQ()) {
                    executorService.submit(() -> consumeDLQ(metadata));
                }
            });

            log.info("DLQ Consumer started with {} threads", threadPoolSize);
        }
    }

    public void stop() {
        if (running.compareAndSet(true, false)) {
            if (executorService != null) {
                executorService.shutdown();
                log.info("DLQ Consumer stopped");
            }
        }
    }

    private void consumeDLQ(HandlerMetadata metadata) {
        String dlqTopic = metadata.getDLQTopicName();

        KafkaConsumer<String, String> consumer = null;

        try {
            consumer = createConsumer(dlqTopic);
            consumer.subscribe(Collections.singletonList(dlqTopic));

            log.info("DLQ consumer started for topic: {}", dlqTopic);

            while (running.get()) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(5));

                for (ConsumerRecord<String, String> record : records) {
                    try {
                        TaskPayload task = objectMapper.readValue(
                            record.value(),
                            TaskPayload.class
                        );

                        String errorMessage = getHeaderValue(record, "error-message");
                        String errorClass = getHeaderValue(record, "error-class");
                        String failedAt = getHeaderValue(record, "failed-at");

                        log.error("DLQ Message: " +
                            "\n  Task ID: {}" +
                            "\n  Routing: {}" +
                            "\n  Retry Count: {}" +
                            "\n  Error Class: {}" +
                            "\n  Error Message: {}" +
                            "\n  Failed At: {}" +
                            "\n  Payload: {}",
                            task.getTaskId(),
                            task.getRoutingKey(),
                            task.getRetryCount(),
                            errorClass,
                            errorMessage,
                            failedAt,
                            objectMapper.writeValueAsString(task.getData())
                        );

                        meterRegistry.counter("dlq.messages",
                            "routing", task.getRoutingKey(),
                            "error_class", errorClass != null ? errorClass : "unknown"
                        ).increment();

                    } catch (Exception e) {
                        log.error("Error processing DLQ message", e);
                    }
                }

                consumer.commitSync();
            }

        } catch (Exception e) {
            log.error("Error in DLQ consumer loop: {}", dlqTopic, e);
        } finally {
            if (consumer != null) {
                try {
                    consumer.close();
                } catch (Exception e) {
                    log.error("Error closing consumer", e);
                }
            }
            log.info("DLQ consumer stopped: {}", dlqTopic);
        }
    }

    private String getHeaderValue(ConsumerRecord<String, String> record, String key) {
        Header header = record.headers().lastHeader(key);
        if (header != null) {
            return new String(header.value(), StandardCharsets.UTF_8);
        }
        return null;
    }

    private KafkaConsumer<String, String> createConsumer(String topic) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "queue-service-dlq-" + topic);
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, "dlq-consumer-" + topic);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10);

        return new KafkaConsumer<>(props);
    }
}
