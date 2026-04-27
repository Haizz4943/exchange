package com.haizz.exchange.wallet.infrastructure.kafka.event;

import java.time.Instant;
import java.util.UUID;

public record UserRegisteredEvent(
        UUID userId,
        String email,
        Instant createdAt,
        String externalProvider
) {}
