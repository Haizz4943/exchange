package com.haizz.exchange.marketdata.infrastructure.provider.binance.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BinanceTradeEvent(
        @JsonProperty("s") String symbol,
        @JsonProperty("t") long tradeId,
        @JsonProperty("p") String price,
        @JsonProperty("q") String quantity,
        @JsonProperty("T") long tradeTime,
        @JsonProperty("m") boolean buyerIsMaker
) {}
