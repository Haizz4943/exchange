package com.haizz.exchange.marketdata.infrastructure.outbox;

import java.time.Instant;
import java.util.UUID;

public record MarketDataOutboxEntry(
        UUID id,
        String eventType,
        String topic,
        String partitionKey,
        String payload,
        int attempts,
        Instant createdAt,
        Instant publishedAt,
        String lastError
) {}
