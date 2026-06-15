package com.haizz.exchange.order.api.dto;

import com.haizz.exchange.order.domain.Order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Compact open-order projection consumed by the Matching Engine on startup
 * (API_SPEC §3.7). Lighter than the public {@link OrderResponse}; uses camelCase
 * field names (internal contract) and renders decimals as plain strings.
 */
public record InternalOrderProjection(
        UUID id,
        UUID userId,
        String pair,
        String side,
        String type,
        String quantity,
        String limitPrice,
        String filledQuantity,
        Instant createdAt
) {
    public static InternalOrderProjection from(Order o) {
        return new InternalOrderProjection(
                o.getId(),
                o.getUserId(),
                o.getPair(),
                o.getSide() != null ? o.getSide().name() : null,
                o.getType() != null ? o.getType().name() : null,
                str(o.getQuantity()),
                str(o.getLimitPrice()),
                str(o.getFilledQuantity()),
                o.getCreatedAt());
    }

    private static String str(BigDecimal v) {
        return v != null ? v.toPlainString() : null;
    }
}
