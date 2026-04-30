package com.haizz.exchange.marketdata.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record UdfConfigResponse(
        @JsonProperty("supports_search") boolean supportsSearch,
        @JsonProperty("supports_group_request") boolean supportsGroupRequest,
        @JsonProperty("supported_resolutions") List<String> supportedResolutions,
        @JsonProperty("supports_marks") boolean supportsMarks,
        @JsonProperty("supports_timescale_marks") boolean supportsTimescaleMarks,
        @JsonProperty("supports_time") boolean supportsTime,
        List<ExchangeEntry> exchanges,
        @JsonProperty("symbols_types") List<SymbolType> symbolsTypes
) {
    public record ExchangeEntry(String value, String name, String desc) {}
    public record SymbolType(String name, String value) {}

    public static UdfConfigResponse defaults() {
        return new UdfConfigResponse(
                true, false,
                List.of("1", "5", "15", "60", "240", "1D"),
                false, false, true,
                List.of(new ExchangeEntry("binance-sim", "Binance (Sim)",
                        "Simulated Binance market")),
                List.of(new SymbolType("crypto", "crypto"))
        );
    }
}
