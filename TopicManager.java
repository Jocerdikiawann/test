package com.company.queue.registry;

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

    /**
     * Create topic jika belum ada
     */
    public void createTopicIfNotExists(HandlerMetadata metadata) {
        try {
            // Main topic
            createTopic(
                metadata.getTopicName(),
                metadata.getPartitions(),
                metadata.getReplicationFactor()
            );

            // Retry topic
            createTopic(
                metadata.getRetryTopicName(),
                metadata.getPartitions(),
                metadata.getReplicationFactor()
            );

            // DLQ topic
            if (metadata.isEnableDLQ()) {
                createTopic(
                    metadata.getDLQTopicName(),
                    metadata.getPartitions(),
                    metadata.getReplicationFactor()
                );
            }

        } catch (Exception e) {
            log.error("Failed to create topics for routing: {}", metadata.getRoutingKey(), e);
            throw new RuntimeException("Topic creation failed", e);
        }
    }

    private void createTopic(String topicName, int partitions, short replicationFactor)
            throws ExecutionException, InterruptedException {

        // Check if topic exists
        if (topicExists(topicName)) {
            log.debug("Topic already exists: {}", topicName);
            return;
        }

        NewTopic newTopic = new NewTopic(topicName, partitions, replicationFactor);

        // Topic configs
        Map<String, String> configs = new HashMap<>();
        configs.put("retention.ms", "604800000"); // 7 days
        configs.put("compression.type", "snappy");
        configs.put("max.message.bytes", "10485760"); // 10MB
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

    /**
     * Get topic info
     */
    public Map<String, TopicDescription> describeTopics(Collection<String> topicNames)
            throws ExecutionException, InterruptedException {
        DescribeTopicsResult result = adminClient.describeTopics(topicNames);
        return result.all().get();
    }

    /**
     * Delete topic (untuk testing/cleanup)
     */
    public void deleteTopic(String topicName) throws ExecutionException, InterruptedException {
        DeleteTopicsResult result = adminClient.deleteTopics(Collections.singleton(topicName));
        result.all().get();
        log.info("🗑️ Deleted topic: {}", topicName);
    }
}
