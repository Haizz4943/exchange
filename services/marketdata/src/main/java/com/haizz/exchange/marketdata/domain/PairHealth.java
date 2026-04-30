package com.haizz.exchange.marketdata.domain;

import java.time.Instant;

public record PairHealth(
        PairSymbol pair,
        Instant tradeLastUpdate,
        Instant depthLastUpdate,
        FeedStatus status
) {
    public static PairHealth disconnected(PairSymbol pair) {
        return new PairHealth(pair, null, null, FeedStatus.DISCONNECTED);
    }
}
