package com.haizz.exchange.marketdata.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record Ticker(
        PairSymbol pair,
        BigDecimal bestBid,
        BigDecimal bestAsk,
        BigDecimal lastPrice,
        Instant updatedAt
) {
    public static Ticker fromDepth(DepthSnapshot depth, BigDecimal lastPrice) {
        BigDecimal bestBid = depth.bids().isEmpty() ? BigDecimal.ZERO : depth.bids().get(0).get(0);
        BigDecimal bestAsk = depth.asks().isEmpty() ? BigDecimal.ZERO : depth.asks().get(0).get(0);
        return new Ticker(depth.pair(), bestBid, bestAsk, lastPrice, Instant.now());
    }
}
