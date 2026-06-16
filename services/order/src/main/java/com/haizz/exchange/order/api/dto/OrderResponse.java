package com.haizz.exchange.order.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.haizz.exchange.order.domain.Order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Place/get order response body (JSON snake_case). Monetary/quantity values are
 * rendered as plain decimal strings (API_SPEC: never floating-point numbers).
 * Null limit_price / avg_fill_price are emitted as JSON null.
 */
public record OrderResponse(
        @JsonProperty("order_id") UUID orderId,
        @JsonProperty("client_order_id") UUID clientOrderId,
        @JsonProperty("pair") String pair,
        @JsonProperty("side") String side,
        @JsonProperty("type") String type,
        @JsonProperty("quantity") String quantity,
        @JsonProperty("limit_price") String limitPrice,
        @JsonProperty("time_in_force") String timeInForce,
        @JsonProperty("state") String state,
        @JsonProperty("filled_qty") String filledQty,
        @JsonProperty("avg_fill_price") String avgFillPrice,
        @JsonProperty("freeze_amount") String freezeAmount,
        @JsonProperty("freeze_asset") String freezeAsset,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt
) {
    public static OrderResponse from(Order o) {
        return new OrderResponse(
                o.getId(),
                o.getClientOrderId(),
                o.getPair(),
                o.getSide() != null ? o.getSide().name() : null,
                o.getType() != null ? o.getType().name() : null,
                str(o.getQuantity()),
                str(o.getLimitPrice()),
                o.getTimeInForce(),
                o.getState() != null ? o.getState().name() : null,
                str(o.getFilledQuantity()),
                str(o.getAvgFillPrice()),
                str(o.getFreezeAmount()),
                o.getFreezeAsset(),
                o.getCreatedAt(),
                o.getUpdatedAt());
    }

    private static String str(BigDecimal v) {
        return v != null ? v.toPlainString() : null;
    }
}
