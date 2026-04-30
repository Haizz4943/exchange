package com.haizz.exchange.marketdata.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.haizz.exchange.marketdata.domain.FeedStatus;
import com.haizz.exchange.marketdata.domain.PairHealth;
import com.haizz.exchange.marketdata.domain.PairSymbol;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

public record HealthResponse(
        Map<String, PairHealthDto> pairs,
        @JsonProperty("binance_ws_connected") boolean binanceWsConnected,
        @JsonProperty("overall_status") String overallStatus
) {
    public record PairHealthDto(
            @JsonProperty("trade_last_update") Instant tradeLastUpdate,
            @JsonProperty("depth_last_update") Instant depthLastUpdate,
            String status
    ) {}

    public static HealthResponse from(Map<PairSymbol, PairHealth> healthMap, boolean wsConnected) {
        var pairs = healthMap.entrySet().stream()
                .collect(Collectors.toMap(
                        e -> e.getKey().value(),
                        e -> new PairHealthDto(
                                e.getValue().tradeLastUpdate(),
                                e.getValue().depthLastUpdate(),
                                e.getValue().status().name()
                        )
                ));

        boolean allHealthy = healthMap.values().stream()
                .allMatch(h -> h.status() == FeedStatus.HEALTHY);
        boolean anyDegraded = healthMap.values().stream()
                .anyMatch(h -> h.status() == FeedStatus.DEGRADED || h.status() == FeedStatus.DISCONNECTED);

        String overall = allHealthy ? "HEALTHY" : anyDegraded ? "DEGRADED" : "STALE";
        return new HealthResponse(pairs, wsConnected, overall);
    }
}
