package com.haizz.exchange.common.event.market;

import java.math.BigDecimal;
import java.time.Instant;

public record PairMetadataUpdatedEvent(
        String pair,
        String field,
        BigDecimal oldValue,
        BigDecimal newValue,
        Instant updatedAt
) {}
