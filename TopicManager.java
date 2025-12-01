package com.company.queue.registry;

import com.company.queue.config.KafkaAdminConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.errors.TopicExistsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ExecutionException;

@ApplicationScoped
public class TopicManager {

    private static final Logger log = LoggerFactory.getLogger(TopicManager.class);

    @Inject
    AdminClient adminClient;

    @Inject
    KafkaAdminConfig kafkaConfig;

    public void createTopicIfNotExists(HandlerMetadata metadata) {
        try {
            // Use config values if annotation uses default
            short replicationFactor = metadata.getReplicationFactor() == 3
                ? kafkaConfig.getReplicationFactor()
                : metadata.getReplicationFactor();

            int partitions = metadata.getPartitions();

            createTopic(metadata.getTopicName(), partitions, replicationFactor);
            createTopic(metadata.getRetryTopicName(), partitions, replicationFactor);

            if (metadata.isEnableDLQ()) {
                createTopic(metadata.getDLQTopicName(), partitions, replicationFactor);
            }

        } catch (Exception e) {
            log.error("Failed to create topics for routing: {}", metadata.getRoutingKey(), e);
            throw new RuntimeException("Topic creation failed", e);
        }
    }

    private void createTopic(String topicName, int partitions, short replicationFactor)
            throws ExecutionException, InterruptedException {

        if (topicExists(topicName)) {
            log.debug("Topic already exists: {}", topicName);
            return;
        }

        NewTopic newTopic = new NewTopic(topicName, partitions, replicationFactor);

        Map<String, String> configs = new HashMap<>();
        configs.put("retention.ms", "604800000");
        configs.put("compression.type", "snappy");
        configs.put("max.message.bytes", "10485760");
        newTopic.configs(configs);

        try {
            CreateTopicsResult result = adminClient.createTopics(Collections.singleton(newTopic));
            result.all().get();
            log.info("✅ Created topic: {} (partitions={}, replication={})",
                topicName, partitions, replicationFactor);

        } catch (ExecutionException e) {
            if (e.getCause() instanceof TopicExistsException) {
                log.debug("Topic already exists: {}", topicName);
            } else {
                throw e;
            }
        }
    }

    private boolean topicExists(String topicName) throws ExecutionException, InterruptedException {
        ListTopicsResult topics = adminClient.listTopics();
        Set<String> topicNames = topics.names().get();
        return topicNames.contains(topicName);
    }
}
