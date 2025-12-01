package com.company.queue.consumer;

import com.company.queue.model.TaskPayload;
import com.company.queue.registry.DynamicConsumerRegistry;
import com.company.queue.registry.HandlerMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@ApplicationScoped
public class RetryConsumer {

    private static final Logger log = LoggerFactory.getLogger(RetryConsumer.class);

    @Inject
    DynamicConsumerRegistry registry;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "kafka.bootstrap.servers")
    String bootstrapServers;

    @ConfigProperty(name = "queue.retry.enabled", defaultValue = "true")
    boolean enabled;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService executorService;

    void onShutdown(@Observes ShutdownEvent event) {
        stop();
    }

    /**
     * Start retry consumer setelah registry initialized
     * Dipanggil dari DynamicConsumerRegistry setelah semua consumer started
     */
    public void start() {
        if (!enabled) {
            log.info("⏭️  Retry Consumer disabled");
            return;
        }

        if (running.compareAndSet(false, true)) {
            int handlerCount = registry.getAllHandlers().size();

            if (handlerCount == 0) {
                log.warn("⚠️  No handlers found, retry consumer not started");
                return;
            }

            // Gunakan minimum 1 thread, maximum sesuai handler count
            int threadPoolSize = Math.max(1, Math.min(handlerCount, 10));

            executorService = Executors.newFixedThreadPool(threadPoolSize);

            // Start retry consumer untuk setiap handler
            registry.getAllHandlers().values().forEach(metadata -> {
                executorService.submit(() -> consumeRetry(metadata));
            });

            log.info("✅ Retry Consumer started with {} threads for {} handlers",
                threadPoolSize, handlerCount);
        }
    }

    public void stop() {
        if (running.compareAndSet(true, false)) {
            if (executorService != null) {
                executorService.shutdown();
                log.info("🛑 Retry Consumer stopped");
            }
        }
    }

    private void consumeRetry(HandlerMetadata metadata) {
        String retryTopic = metadata.getRetryTopicName();

        KafkaConsumer<String, String> consumer = null;
        KafkaProducer<String, String> producer = null;

        try {
            consumer = createConsumer(retryTopic);
            producer = createProducer();

            consumer.subscribe(Collections.singletonList(retryTopic));

            log.info("🔄 Retry consumer started for topic: {}", retryTopic);

            while (running.get()) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(5));

                for (ConsumerRecord<String, String> record : records) {
                    try {
                        TaskPayload task = objectMapper.readValue(
                            record.value(),
                            TaskPayload.class
                        );

                        Instant now = Instant.now();
                        Instant scheduledAt = task.getScheduledAt();

                        if (scheduledAt == null || now.isAfter(scheduledAt)) {
                            // Sudah waktunya retry, kirim ke main topic
                            log.info("⏰ Retrying task: id={}, routing={}, retry={}",
                                task.getTaskId(),
                                task.getRoutingKey(),
                                task.getRetryCount()
                            );

                            producer.send(new ProducerRecord<>(
                                metadata.getTopicName(),
                                task.getTaskId(),
                                objectMapper.writeValueAsString(task)
                            ));

                        } else {
                            // Belum waktunya, seek back
                            long delaySeconds = Duration.between(now, scheduledAt).getSeconds();
                            log.debug("⏳ Task not ready yet, delay: {}s, id={}",
                                delaySeconds, task.getTaskId());

                            consumer.seek(
                                record.topicPartition(),
                                record.offset()
                            );

                            Thread.sleep(Math.min(delaySeconds * 1000, 30000));
                            break;
                        }

                    } catch (Exception e) {
                        log.error("Error processing retry message", e);
                    }
                }

                consumer.commitSync();
            }

        } catch (Exception e) {
            log.error("Error in retry consumer loop: {}", retryTopic, e);
        } finally {
            if (consumer != null) {
                try {
                    consumer.close();
                } catch (Exception e) {
                    log.error("Error closing consumer", e);
                }
            }
            if (producer != null) {
                try {
                    producer.close();
                } catch (Exception e) {
                    log.error("Error closing producer", e);
                }
            }
            log.info("🛑 Retry consumer stopped: {}", retryTopic);
        }
    }

    private KafkaConsumer<String, String> createConsumer(String topic) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "queue-service-retry-" + topic);
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, "retry-consumer-" + topic);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10);

        return new KafkaConsumer<>(props);
    }

    private KafkaProducer<String, String> createProducer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
            StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
            StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");

        return new KafkaProducer<>(props);
    }
}
