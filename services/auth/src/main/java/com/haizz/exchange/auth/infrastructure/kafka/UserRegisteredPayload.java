package com.haizz.exchange.auth.infrastructure.kafka;

import java.time.Instant;
import java.util.UUID;

public record UserRegisteredPayload(
        UUID userId,
        String email,
        Instant createdAt,
        String externalProvider
) {
}
