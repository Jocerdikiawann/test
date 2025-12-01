package com.company.queue.registry;

import com.company.queue.annotation.QueueHandler;
import com.company.queue.consumer.DLQConsumer;
import com.company.queue.consumer.RetryConsumer;
import com.company.queue.service.TaskExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

@ApplicationScoped
public class DynamicConsumerRegistry {

    private static final Logger log = LoggerFactory.getLogger(DynamicConsumerRegistry.class);

    private final Map<String, HandlerMetadata> handlers = new ConcurrentHashMap<>();
    private final Map<String, DynamicConsumer> consumers = new ConcurrentHashMap<>();

    @Inject
    Instance<Object> beans;

    @Inject
    TopicManager topicManager;

    @Inject
    ExecutorService executorService;

    @Inject
    TaskExecutor taskExecutor;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    RetryConsumer retryConsumer;

    @Inject
    DLQConsumer dlqConsumer;

    @ConfigProperty(name = "kafka.bootstrap.servers")
    String bootstrapServers;

    void onStart(@Observes StartupEvent event) {
        log.info("🚀 Starting Dynamic Consumer Registry...");

        scanHandlers();
        createTopics();
        startConsumers();

        // Start retry & DLQ consumers SETELAH main consumers started
        startRetryAndDLQConsumers();

        log.info("✅ Registry started. Handlers: {}, Consumers: {}",
            handlers.size(), consumers.size());
    }

    void onShutdown(@Observes ShutdownEvent event) {
        log.info("🛑 Stopping all consumers...");
        consumers.values().forEach(DynamicConsumer::stop);
        log.info("✅ All consumers stopped");
    }

    private void scanHandlers() {
        beans.stream().forEach(bean -> {
            for (Method method : bean.getClass().getDeclaredMethods()) {
                if (method.isAnnotationPresent(QueueHandler.class)) {
                    QueueHandler annotation = method.getAnnotation(QueueHandler.class);
                    String routingKey = annotation.value();
                    String topicName = annotation.topic().isEmpty()
                        ? routingKeyToTopicName(routingKey)
                        : annotation.topic();

                    if (handlers.containsKey(routingKey)) {
                        throw new IllegalStateException("Duplicate routing: " + routingKey);
                    }

                    HandlerMetadata metadata = new HandlerMetadata(
                        routingKey, topicName, bean, method, annotation
                    );

                    handlers.put(routingKey, metadata);
                    log.info("📝 Registered: {} → {}", routingKey, topicName);
                }
            }
        });
    }

    private void createTopics() {
        handlers.values().forEach(metadata -> {
            try {
                topicManager.createTopicIfNotExists(metadata);
            } catch (Exception e) {
                log.error("Failed to create topics for: {}", metadata.getRoutingKey(), e);
                throw new RuntimeException(e);
            }
        });
    }

    private void startConsumers() {
        handlers.values().forEach(metadata -> {
            try {
                DynamicConsumer consumer = new DynamicConsumer(
                    metadata,
                    bootstrapServers,
                    executorService,
                    taskExecutor,
                    meterRegistry,
                    objectMapper
                );
                consumer.start();
                consumers.put(metadata.getRoutingKey(), consumer);
            } catch (Exception e) {
                log.error("Failed to start consumer: {}", metadata.getRoutingKey(), e);
                throw new RuntimeException(e);
            }
        });
    }

    private void startRetryAndDLQConsumers() {
        try {
            // Start retry consumer
            retryConsumer.start();

            // Start DLQ consumer
            dlqConsumer.start();

        } catch (Exception e) {
            log.error("Failed to start retry/DLQ consumers", e);
        }
    }

    private String routingKeyToTopicName(String routingKey) {
        return routingKey.replace(".", "-") + "-tasks";
    }

    public HandlerMetadata getHandler(String routingKey) {
        return handlers.get(routingKey);
    }

    public Map<String, HandlerMetadata> getAllHandlers() {
        return new HashMap<>(handlers);
    }
}
