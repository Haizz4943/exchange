package com.haizz.exchange.marketdata.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.haizz.exchange.marketdata.domain.DepthSnapshot;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record DepthResponse(
        String pair,
        List<List<String>> bids,
        List<List<String>> asks,
        @JsonProperty("updated_at") Instant updatedAt
) {
    public static DepthResponse from(DepthSnapshot snapshot) {
        return new DepthResponse(
                snapshot.pair().value(),
                toStringLevels(snapshot.bids()),
                toStringLevels(snapshot.asks()),
                snapshot.updatedAt()
        );
    }

    private static List<List<String>> toStringLevels(List<List<BigDecimal>> levels) {
        return levels.stream()
                .map(level -> level.stream()
                        .map(v -> v.stripTrailingZeros().toPlainString())
                        .toList())
                .toList();
    }
}
