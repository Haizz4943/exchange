package com.haizz.exchange.matching.infrastructure.index;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-pair single-threaded execution model.
 *
 * <p>Maintains exactly one single-thread {@link ExecutorService} per pair symbol
 * (lazily created). Every task submitted for a pair runs on that pair's dedicated
 * thread, so all work for a pair is serialized into a deterministic FIFO sequence.
 *
 * <p><b>Contract:</b> ALL mutation of {@code OpenOrdersIndex} and ALL matching for
 * a given pair MUST be dispatched through {@link #submit(String, Runnable)}. This is
 * what makes the non-concurrent {@code OpenOrdersIndex} safe — there is never more
 * than one thread touching a given pair's book at a time.
 */
@Slf4j
@Component
public class PairExecutorRegistry {

    private final Map<String, ExecutorService> executors = new ConcurrentHashMap<>();

    /**
     * Runs {@code task} on the dedicated single thread for {@code pair}.
     * Tasks for the same pair execute serially in submission order.
     */
    public void submit(String pair, Runnable task) {
        ExecutorService executor = executors.computeIfAbsent(pair, this::newExecutor);
        executor.execute(() -> {
            try {
                task.run();
            } catch (Exception e) {
                // Never let a task failure kill the pair's worker thread.
                log.error("Task failed on pair executor pair={}", pair, e);
            }
        });
    }

    private ExecutorService newExecutor(String pair) {
        log.info("Creating dedicated executor for pair={}", pair);
        AtomicInteger seq = new AtomicInteger();
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "pair-" + pair + "-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void shutdown() {
        log.info("Shutting down {} pair executor(s)", executors.size());
        executors.forEach((pair, executor) -> {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        });
        executors.clear();
    }
}
