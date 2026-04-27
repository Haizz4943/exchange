package com.haizz.exchange.wallet.infrastructure.kafka.event;

import java.time.Instant;

public record EventEnvelope<T>(
        String eventId,
        String eventType,
        int version,
        Instant timestamp,
        String source,
        String correlationId,
        T payload
) {}
