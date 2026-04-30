package com.haizz.exchange.marketdata.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record TradeObservation(
        PairSymbol pair,
        BigDecimal price,
        BigDecimal quantity,
        boolean buyerIsMaker,
        long externalTradeId,
        Instant observedAt
) {
    public boolean isValid() {
        return price != null && price.compareTo(BigDecimal.ZERO) > 0
                && quantity != null && quantity.compareTo(BigDecimal.ZERO) > 0;
    }
}
