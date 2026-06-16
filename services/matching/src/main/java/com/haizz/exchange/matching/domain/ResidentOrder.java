package com.haizz.exchange.matching.domain;

import com.haizz.exchange.common.enums.OrderSide;
import com.haizz.exchange.common.enums.OrderType;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Plain (non-JPA) representation of an open order held in the in-memory
 * matching index. Mutable {@code filledQuantity} is updated as fills arrive.
 */
@Getter
public class ResidentOrder {

    private final UUID orderId;
    private final UUID userId;
    private final String pair;
    private final OrderSide side;
    private final OrderType type;
    private final BigDecimal totalQuantity;
    /** {@code null} for MARKET orders. */
    private final BigDecimal limitPrice;
    private final Instant createdAt;

    private BigDecimal filledQuantity;

    public ResidentOrder(UUID orderId, UUID userId, String pair, OrderSide side,
                         OrderType type, BigDecimal totalQuantity, BigDecimal limitPrice,
                         Instant createdAt, BigDecimal filledQuantity) {
        this.orderId = orderId;
        this.userId = userId;
        this.pair = pair;
        this.side = side;
        this.type = type;
        this.totalQuantity = totalQuantity;
        this.limitPrice = limitPrice;
        this.createdAt = createdAt;
        this.filledQuantity = filledQuantity == null ? BigDecimal.ZERO : filledQuantity;
    }

    public BigDecimal remainingQuantity() {
        return totalQuantity.subtract(filledQuantity);
    }

    public void addFill(BigDecimal qty) {
        this.filledQuantity = this.filledQuantity.add(qty);
    }

    public boolean isFullyFilled() {
        return remainingQuantity().compareTo(BigDecimal.ZERO) <= 0;
    }
}
