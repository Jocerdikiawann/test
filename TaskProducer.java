package com.company.queue.service;

import com.company.queue.model.TaskPayload;
import com.company.queue.registry.HandlerMetadata;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;

import java.time.Instant;

@ApplicationScoped
public class TaskProducer {

    @Inject
    @Channel("tasks-retry-out")
    Emitter<TaskPayload> retryEmitter;

    @Inject
    @Channel("tasks-dlq-out")
    Emitter<TaskPayload> dlqEmitter;

    public Uni<Void> sendToRetry(TaskPayload task, HandlerMetadata metadata) {
        long delaySeconds = (long) Math.pow(2, task.getRetryCount())
            * metadata.getRetryDelayMultiplier();
        Instant retryAt = Instant.now().plusSeconds(delaySeconds);

        task.setScheduledAt(retryAt);

        Message<TaskPayload> message = Message.of(task)
            .addMetadata(OutgoingKafkaRecordMetadata.<String>builder()
                .withTopic(metadata.getRetryTopicName())
                .withKey(task.getTaskId())
                .withHeaders(new RecordHeaders()
                    .add("routing-key", task.getRoutingKey().getBytes())
                    .add("retry-count", String.valueOf(task.getRetryCount()).getBytes())
                    .add("retry-at", retryAt.toString().getBytes())
                )
                .build());

        return Uni.createFrom().completionStage(retryEmitter.send(message));
    }

    public Uni<Void> sendToDLQ(TaskPayload task, HandlerMetadata metadata, Throwable error) {
        Message<TaskPayload> message = Message.of(task)
            .addMetadata(OutgoingKafkaRecordMetadata.<String>builder()
                .withTopic(metadata.getDLQTopicName())
                .withKey(task.getTaskId())
                .withHeaders(new RecordHeaders()
                    .add("routing-key", task.getRoutingKey().getBytes())
                    .add("error-message", error.getMessage().getBytes())
                    .add("error-class", error.getClass().getName().getBytes())
                    .add("failed-at", Instant.now().toString().getBytes())
                )
                .build());

        return Uni.createFrom().completionStage(dlqEmitter.send(message));
    }
}
