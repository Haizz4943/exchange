package com.haizz.exchange.marketdata.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record Candlestick(
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
        Instant closeTime
) {}
