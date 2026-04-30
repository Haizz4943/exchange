package com.haizz.exchange.marketdata.infrastructure.provider.binance.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BinanceExchangeInfo(
        @JsonProperty("symbols") List<SymbolInfo> symbols
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SymbolInfo(
            @JsonProperty("symbol") String symbol,
            @JsonProperty("status") String status,
            @JsonProperty("baseAsset") String baseAsset,
            @JsonProperty("quoteAsset") String quoteAsset,
            @JsonProperty("filters") List<Map<String, String>> filters
    ) {
        public String tickSize() {
            return filters.stream()
                    .filter(f -> "PRICE_FILTER".equals(f.get("filterType")))
                    .map(f -> f.get("tickSize"))
                    .findFirst()
                    .orElse("0.01");
        }

        public String stepSize() {
            return filters.stream()
                    .filter(f -> "LOT_SIZE".equals(f.get("filterType")))
                    .map(f -> f.get("stepSize"))
                    .findFirst()
                    .orElse("0.00001");
        }

        public String minNotional() {
            return filters.stream()
                    .filter(f -> "NOTIONAL".equals(f.get("filterType")) || "MIN_NOTIONAL".equals(f.get("filterType")))
                    .map(f -> f.getOrDefault("minNotional", "10"))
                    .findFirst()
                    .orElse("10");
        }
    }
}
