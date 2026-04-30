package com.haizz.exchange.common.event.market;

import java.math.BigDecimal;
import java.time.Instant;

public record KlineUpdatedEvent(
        String pair,
        String interval,
        Instant openTime,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal volume,
        boolean closed,
        Instant updatedAt
) {}
