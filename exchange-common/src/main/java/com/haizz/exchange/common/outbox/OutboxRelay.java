package com.haizz.exchange.common.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;
import java.util.UUID;

@Slf4j
public abstract class OutboxRelay {

    protected abstract List<OutboxEntry> fetchUnpublished(int batchSize);

    protected abstract void markPublished(UUID id);

    protected abstract void incrementAttempts(UUID id, String error);

    protected abstract void moveToDeadLetter(UUID id);

    protected abstract KafkaTemplate<String, String> kafkaTemplate();

    protected int maxAttempts() {
        return 10;
    }

    @Scheduled(fixedDelayString = "${outbox.relay.poll-interval-ms:100}")
    public void relay() {
        List<OutboxEntry> entries = fetchUnpublished(100);
        if (entries.isEmpty()) return;

        log.debug("Relaying {} outbox entries", entries.size());
        for (OutboxEntry entry : entries) {
            try {
                kafkaTemplate().send(entry.topic(), entry.partitionKey(), entry.payload()).get();
                markPublished(entry.id());
            } catch (Exception e) {
                log.error("Failed to publish outbox entry {} (attempt {}): {}", entry.id(), entry.attempts() + 1, e.getMessage());
                if (entry.attempts() + 1 >= maxAttempts()) {
                    log.error("Moving outbox entry {} to dead letter after {} attempts", entry.id(), maxAttempts());
                    moveToDeadLetter(entry.id());
                } else {
                    incrementAttempts(entry.id(), e.getMessage());
                }
            }
        }
    }
}
