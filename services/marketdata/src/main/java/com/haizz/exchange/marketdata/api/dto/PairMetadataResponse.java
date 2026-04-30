package com.haizz.exchange.marketdata.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.haizz.exchange.marketdata.domain.PairMetadata;

import java.time.Instant;

public record PairMetadataResponse(
        String symbol,
        @JsonProperty("base_asset") String baseAsset,
        @JsonProperty("quote_asset") String quoteAsset,
        @JsonProperty("tick_size") String tickSize,
        @JsonProperty("step_size") String stepSize,
        @JsonProperty("min_notional") String minNotional,
        String status,
        @JsonProperty("updated_at") Instant updatedAt
) {
    public static PairMetadataResponse from(PairMetadata meta) {
        return new PairMetadataResponse(
                meta.symbol().value(),
                meta.baseAsset(),
                meta.quoteAsset(),
                meta.tickSize().stripTrailingZeros().toPlainString(),
                meta.stepSize().stripTrailingZeros().toPlainString(),
                meta.minNotional().stripTrailingZeros().toPlainString(),
                meta.status(),
                meta.updatedAt()
        );
    }
}
