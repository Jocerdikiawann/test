package com.company.queue.registry;

import com.company.queue.annotation.QueueHandler;

import java.lang.reflect.Method;

public class HandlerMetadata {

    private final String routingKey;
    private final String topicName;
    private final Object bean;
    private final Method method;

    // Annotation properties
    private final String description;
    private final int concurrency;
    private final int maxPollRecords;
    private final int maxPollIntervalMs;
    private final int sessionTimeoutMs;
    private final int timeout;
    private final int maxRetries;
    private final int retryDelayMultiplier;
    private final int partitions;
    private final short replicationFactor;
    private final boolean enableDLQ;
    private final int priority;

    public HandlerMetadata(
        String routingKey,
        String topicName,
        Object bean,
        Method method,
        QueueHandler annotation
    ) {
        this.routingKey = routingKey;
        this.topicName = topicName;
        this.bean = bean;
        this.method = method;

        this.description = annotation.description();
        this.concurrency = annotation.concurrency();
        this.maxPollRecords = annotation.maxPollRecords();
        this.maxPollIntervalMs = annotation.maxPollIntervalMs();
        this.sessionTimeoutMs = annotation.sessionTimeoutMs();
        this.timeout = annotation.timeout();
        this.maxRetries = annotation.maxRetries();
        this.retryDelayMultiplier = annotation.retryDelayMultiplier();
        this.partitions = annotation.partitions();
        this.replicationFactor = annotation.replicationFactor();
        this.enableDLQ = annotation.enableDLQ();
        this.priority = annotation.priority();

        method.setAccessible(true);
    }

    public Object invoke(Object... args) throws Exception {
        return method.invoke(bean, args);
    }

    // Getters
    public String getRoutingKey() {
        return routingKey;
    }

    public String getTopicName() {
        return topicName;
    }

    public Object getBean() {
        return bean;
    }

    public Method getMethod() {
        return method;
    }

    public String getDescription() {
        return description;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public int getMaxPollRecords() {
        return maxPollRecords;
    }

    public int getMaxPollIntervalMs() {
        return maxPollIntervalMs;
    }

    public int getSessionTimeoutMs() {
        return sessionTimeoutMs;
    }

    public int getTimeout() {
        return timeout;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public int getRetryDelayMultiplier() {
        return retryDelayMultiplier;
    }

    public int getPartitions() {
        return partitions;
    }

    public short getReplicationFactor() {
        return replicationFactor;
    }

    public boolean isEnableDLQ() {
        return enableDLQ;
    }

    public int getPriority() {
        return priority;
    }

    public String getRetryTopicName() {
        return topicName + "-retry";
    }

    public String getDLQTopicName() {
        return topicName + "-dlq";
    }
}
