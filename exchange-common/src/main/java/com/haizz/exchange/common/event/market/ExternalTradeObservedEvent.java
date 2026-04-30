package com.haizz.exchange.common.event.market;

import java.math.BigDecimal;
import java.time.Instant;

public record ExternalTradeObservedEvent(
        String pair,
        BigDecimal price,
        BigDecimal quantity,
        boolean buyerIsMaker,
        long externalTradeId,
        Instant eventTime
) {}
