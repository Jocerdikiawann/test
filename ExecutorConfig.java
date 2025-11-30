package com.company.queue.config;

import io.quarkus.runtime.Shutdown;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class ExecutorConfig {

    private static final Logger log = LoggerFactory.getLogger(ExecutorConfig.class);

    @ConfigProperty(name = "queue.executor.pool-size", defaultValue = "50")
    int poolSize;

    private ExecutorService executorService;

    @Produces
    @ApplicationScoped
    public ExecutorService executorService() {
        if (executorService == null) {
            executorService = Executors.newFixedThreadPool(
                poolSize,
                r -> {
                    Thread thread = new Thread(r);
                    thread.setName("queue-worker-" + thread.getId());
                    thread.setDaemon(false);
                    return thread;
                }
            );
            log.info("✅ Executor Service created with pool size: {}", poolSize);
        }
        return executorService;
    }

    @Shutdown
    void onShutdown() {
        if (executorService != null) {
            log.info("🛑 Shutting down Executor Service...");
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
                log.info("✅ Executor Service shut down completed");
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
