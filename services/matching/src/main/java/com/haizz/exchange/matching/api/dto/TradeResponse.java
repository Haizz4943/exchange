package com.haizz.exchange.matching.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.haizz.exchange.matching.domain.Trade;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Public (snake_case) view of a {@link Trade} returned by {@code GET /api/v1/trades}.
 */
public record TradeResponse(
        UUID id,
        @JsonProperty("order_id") UUID orderId,
        String pair,
        @JsonProperty("base_asset") String baseAsset,
        @JsonProperty("quote_asset") String quoteAsset,
        String side,
        BigDecimal price,
        BigDecimal quantity,
        @JsonProperty("quote_quantity") BigDecimal quoteQuantity,
        @JsonProperty("fee_amount") BigDecimal feeAmount,
        @JsonProperty("fee_asset") String feeAsset,
        String role,
        @JsonProperty("executed_at") Instant executedAt
) {
    public static TradeResponse from(Trade t) {
        return new TradeResponse(
                t.getId(), t.getOrderId(), t.getPair(), t.getBaseAsset(), t.getQuoteAsset(),
                t.getSide().name(), t.getPrice(), t.getQuantity(), t.getQuoteAmount(),
                t.getFeeAmount(), t.getFeeAsset(), t.getRole().name(), t.getExecutedAt());
    }
}
