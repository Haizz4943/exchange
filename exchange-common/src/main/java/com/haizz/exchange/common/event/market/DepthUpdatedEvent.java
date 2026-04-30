package com.haizz.exchange.common.event.market;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record DepthUpdatedEvent(
        String pair,
        List<List<BigDecimal>> bids,
        List<List<BigDecimal>> asks,
        Instant updatedAt
) {}
