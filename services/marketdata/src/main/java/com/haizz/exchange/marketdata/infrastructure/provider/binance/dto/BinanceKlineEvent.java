package com.haizz.exchange.marketdata.infrastructure.provider.binance.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BinanceKlineEvent(
        @JsonProperty("s") String symbol,
        @JsonProperty("k") KlineData kline
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KlineData(
            @JsonProperty("t") long openTime,
            @JsonProperty("T") long closeTime,
            @JsonProperty("s") String symbol,
            @JsonProperty("i") String interval,
            @JsonProperty("o") String open,
            @JsonProperty("c") String close,
            @JsonProperty("h") String high,
            @JsonProperty("l") String low,
            @JsonProperty("v") String volume,
            @JsonProperty("q") String quoteVolume,
            @JsonProperty("n") int tradeCount,
            @JsonProperty("x") boolean closed
    ) {}
}
