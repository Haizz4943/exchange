package com.haizz.exchange.marketdata.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record KlineUpdate(
        PairSymbol pair,
        Interval interval,
        Instant openTime,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal volume,
        BigDecimal quoteVolume,
        int tradeCount,
        Instant closeTime,
        boolean closed
) {
    public Candlestick toCandlestick() {
        return new Candlestick(pair, interval, openTime, open, high, low, close, volume, quoteVolume, tradeCount, closeTime);
    }
}
