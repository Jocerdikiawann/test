package com.company.queue.registry;

import com.company.queue.model.TaskPayload;
import com.company.queue.service.TaskProducer;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;

@ApplicationScoped
public class TaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(TaskExecutor.class);

    @Inject
    ExecutorService executorService;

    @Inject
    TaskProducer taskProducer;

    public Uni<Object> execute(TaskPayload task, HandlerMetadata metadata) {
        return Uni.createFrom().completionStage(() ->
            executorService.submit(() -> {
                try {
                    return metadata.invoke(task);
                } catch (Exception e) {
                    throw new RuntimeException("Handler execution failed", e);
                }
            })
        )
        .ifNoItem().after(Duration.ofSeconds(metadata.getTimeout()))
            .failWith(() -> new TimeoutException(
                "Task timeout: " + task.getRoutingKey()
            ));
    }

    public Uni<Void> sendToRetry(TaskPayload task, HandlerMetadata metadata) {
        task.incrementRetry();

        log.info("🔄 Retry task: id={}, routing={}, retry={}",
            task.getTaskId(),
            task.getRoutingKey(),
            task.getRetryCount()
        );

        return taskProducer.sendToRetry(task, metadata);
    }

    public Uni<Void> sendToDLQ(TaskPayload task, HandlerMetadata metadata, Throwable error) {
        log.error("💀 DLQ task: id={}, routing={}",
            task.getTaskId(),
            task.getRoutingKey()
        );

        return taskProducer.sendToDLQ(task, metadata, error);
    }
}
