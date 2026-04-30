package com.haizz.exchange.common.event.market;

import java.time.Instant;

public record MarketDataFeedDegradedEvent(
        String pair,
        String reason,
        String status,
        Instant degradedSince
) {}
