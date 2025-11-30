package com.company.queue.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TaskPayload {

    private String taskId;
    private String routingKey;
    private JsonNode data;

    private Instant createdAt;
    private Instant scheduledAt;

    private Integer retryCount;
    private Integer hopCount;

    // Metadata
    private String sourceService;
    private String traceId;
    private String correlationId;
    private Map<String, String> headers;

    // Error tracking
    private String lastError;
    private Instant lastErrorAt;

    public TaskPayload() {
        this.taskId = UUID.randomUUID().toString();
        this.createdAt = Instant.now();
        this.retryCount = 0;
        this.hopCount = 0;
        this.headers = new HashMap<>();
    }

    public TaskPayload(String routingKey, JsonNode data) {
        this();
        this.routingKey = routingKey;
        this.data = data;
    }

    // Getters and Setters
    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getRoutingKey() {
        return routingKey;
    }

    public void setRoutingKey(String routingKey) {
        this.routingKey = routingKey;
    }

    public JsonNode getData() {
        return data;
    }

    public void setData(JsonNode data) {
        this.data = data;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public void setScheduledAt(Instant scheduledAt) {
        this.scheduledAt = scheduledAt;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public Integer getHopCount() {
        return hopCount;
    }

    public void setHopCount(Integer hopCount) {
        this.hopCount = hopCount;
    }

    public String getSourceService() {
        return sourceService;
    }

    public void setSourceService(String sourceService) {
        this.sourceService = sourceService;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Instant getLastErrorAt() {
        return lastErrorAt;
    }

    public void setLastErrorAt(Instant lastErrorAt) {
        this.lastErrorAt = lastErrorAt;
    }

    public void incrementRetry() {
        this.retryCount++;
    }

    public void incrementHop() {
        this.hopCount++;
    }

    public void recordError(Throwable throwable) {
        this.lastError = throwable.getMessage();
        this.lastErrorAt = Instant.now();
    }
}
