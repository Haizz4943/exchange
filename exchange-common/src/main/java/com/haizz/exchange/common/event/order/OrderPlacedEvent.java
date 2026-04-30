package com.haizz.exchange.common.event.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderPlacedEvent(
        UUID orderId,
        UUID userId,
        String pair,
        String side,
        String type,
        BigDecimal quantity,
        BigDecimal price,
        Instant placedAt
) {}
