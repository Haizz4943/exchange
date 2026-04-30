package com.haizz.exchange.common.outbox;

import java.time.Instant;
import java.util.UUID;

public record OutboxEntry(
        UUID id,
        String eventType,
        String topic,
        String partitionKey,
        String payload,
        int attempts,
        Instant createdAt,
        Instant publishedAt,
        String lastError
) {
    public boolean isPublished() {
        return publishedAt != null;
    }
}
