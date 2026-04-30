package com.haizz.exchange.marketdata.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record PairMetadata(
        PairSymbol symbol,
        String baseAsset,
        String quoteAsset,
        BigDecimal tickSize,
        BigDecimal stepSize,
        BigDecimal minNotional,
        String status,
        Instant updatedAt
) {
    public int pricescale() {
        if (tickSize == null || tickSize.compareTo(BigDecimal.ZERO) == 0) return 100;
        return (int) Math.round(1.0 / tickSize.doubleValue());
    }
}
