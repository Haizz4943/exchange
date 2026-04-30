package com.haizz.exchange.common.event.market;

import java.time.Instant;

public record MarketDataFeedRecoveredEvent(
        String pair,
        Instant recoveredAt
) {}
