package com.company.queue.model;

import java.time.Instant;

public class TaskResult {

    private String taskId;
    private boolean success;
    private Object result;
    private String error;
    private Instant completedAt;
    private long durationMs;

    public TaskResult() {
        this.completedAt = Instant.now();
    }

    public static TaskResult success(String taskId, Object result, long durationMs) {
        TaskResult tr = new TaskResult();
        tr.taskId = taskId;
        tr.success = true;
        tr.result = result;
        tr.durationMs = durationMs;
        return tr;
    }

    public static TaskResult failure(String taskId, String error, long durationMs) {
        TaskResult tr = new TaskResult();
        tr.taskId = taskId;
        tr.success = false;
        tr.error = error;
        tr.durationMs = durationMs;
        return tr;
    }

    // Getters and Setters
    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public Object getResult() {
        return result;
    }

    public void setResult(Object result) {
        this.result = result;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }
}
