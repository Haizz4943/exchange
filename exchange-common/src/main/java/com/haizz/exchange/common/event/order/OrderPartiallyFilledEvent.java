package com.haizz.exchange.common.event.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderPartiallyFilledEvent(
        UUID orderId,
        UUID userId,
        String pair,
        BigDecimal filledQuantity,
        BigDecimal remainingQuantity,
        BigDecimal fillPrice,
        Instant filledAt
) {}
