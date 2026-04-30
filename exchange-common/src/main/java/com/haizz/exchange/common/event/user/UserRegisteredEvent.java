package com.haizz.exchange.common.event.user;

import java.time.Instant;
import java.util.UUID;

public record UserRegisteredEvent(
        UUID userId,
        String email,
        String username,
        Instant registeredAt
) {}
