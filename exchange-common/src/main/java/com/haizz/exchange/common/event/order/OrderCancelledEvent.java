package com.haizz.exchange.common.event.order;

import java.time.Instant;
import java.util.UUID;

public record OrderCancelledEvent(
        UUID orderId,
        UUID userId,
        String pair,
        String reason,
        Instant cancelledAt
) {}
