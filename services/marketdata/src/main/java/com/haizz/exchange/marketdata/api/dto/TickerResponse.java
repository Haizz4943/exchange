package com.haizz.exchange.marketdata.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.haizz.exchange.marketdata.domain.Ticker;

import java.math.BigDecimal;
import java.time.Instant;

public record TickerResponse(
        String pair,
        @JsonProperty("best_bid") String bestBid,
        @JsonProperty("best_ask") String bestAsk,
        @JsonProperty("last_price") String lastPrice,
        @JsonProperty("updated_at") Instant updatedAt
) {
    public static TickerResponse from(Ticker ticker) {
        return new TickerResponse(
                ticker.pair().value(),
                plain(ticker.bestBid()),
                plain(ticker.bestAsk()),
                plain(ticker.lastPrice()),
                ticker.updatedAt()
        );
    }

    private static String plain(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }
}
