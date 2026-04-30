package com.haizz.exchange.marketdata.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.haizz.exchange.marketdata.domain.PairMetadata;

import java.time.Instant;
import java.util.List;

public record ExchangeInfoResponse(
        List<PairMetadataResponse> pairs,
        @JsonProperty("updated_at") Instant updatedAt
) {
    public static ExchangeInfoResponse from(List<PairMetadata> metadataList) {
        return new ExchangeInfoResponse(
                metadataList.stream().map(PairMetadataResponse::from).toList(),
                Instant.now()
        );
    }
}
