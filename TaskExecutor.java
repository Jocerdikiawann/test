package com.company.queue.service;

import com.company.queue.model.TaskPayload;
import com.company.queue.registry.HandlerMetadata;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeoutException;

@ApplicationScoped
public class TaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(TaskExecutor.class);

    @Inject
    ManagedExecutor executor;

    @Inject
    TaskProducer taskProducer;

    /**
     * Execute task dengan timeout handling
     */
    public CompletionStage<Object> execute(TaskPayload task, HandlerMetadata handler) {

        return Uni.createFrom().completionStage(() -> {

            if (handler.isAsync()) {
                // Execute in separate thread pool
                return executor.supplyAsync(() -> invokeHandler(handler, task));
            } else {
                // Execute in current thread
                return CompletableFuture.completedFuture(invokeHandler(handler, task));
            }
        })
        .ifNoItem().after(Duration.ofSeconds(handler.getTimeout()))
            .failWith(() -> new TimeoutException(
                "Task execution timeout: " + task.getRoutingKey()
            ))
        .subscribeAsCompletionStage();
    }

    private Object invokeHandler(HandlerMetadata handler, TaskPayload task) {
        try {
            // Invoke handler method
            return handler.invoke(task);

        } catch (Exception e) {
            log.error("Handler invocation failed: {}", handler.getRoutingKey(), e);
            throw new RuntimeException("Handler execution failed", e);
        }
    }

    /**
     * Send task to retry topic
     */
    public CompletionStage<Void> sendToRetry(TaskPayload task, Throwable error) {
        task.setRetryCount(task.getRetryCount() + 1);

        log.info("Sending task to retry: id={}, routing={}, retry={}",
            task.getTaskId(),
            task.getRoutingKey(),
            task.getRetryCount()
        );

        return taskProducer.sendToRetry(task);
    }

    /**
     * Send task to DLQ
     */
    public CompletionStage<Void> sendToDLQ(TaskPayload task, Throwable error) {

        log.error("Sending task to DLQ: id={}, routing={}",
            task.getTaskId(),
            task.getRoutingKey()
        );

        return taskProducer.sendToDLQ(task, error);
    }
}
