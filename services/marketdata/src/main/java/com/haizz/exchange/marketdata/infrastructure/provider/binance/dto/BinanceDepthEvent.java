package com.haizz.exchange.marketdata.infrastructure.provider.binance.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BinanceDepthEvent(
        @JsonProperty("lastUpdateId") long lastUpdateId,
        @JsonProperty("bids") List<List<String>> bids,
        @JsonProperty("asks") List<List<String>> asks
) {}
