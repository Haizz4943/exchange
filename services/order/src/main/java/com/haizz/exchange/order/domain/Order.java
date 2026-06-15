package com.haizz.exchange.order.domain;

import com.haizz.exchange.common.enums.OrderSide;
import com.haizz.exchange.common.enums.OrderType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
public class Order {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "client_order_id")
    private UUID clientOrderId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "pair", nullable = false, length = 20)
    private String pair;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false, length = 4)
    private OrderSide side;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 8)
    private OrderType type;

    @Column(name = "quantity", nullable = false, precision = 36, scale = 18)
    private BigDecimal quantity;

    @Column(name = "limit_price", precision = 36, scale = 18)
    private BigDecimal limitPrice;

    @Column(name = "time_in_force", nullable = false, length = 8)
    private String timeInForce;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 24)
    private OrderState state;

    @Column(name = "filled_quantity", nullable = false, precision = 36, scale = 18)
    private BigDecimal filledQuantity;

    @Column(name = "avg_fill_price", precision = 36, scale = 18)
    private BigDecimal avgFillPrice;

    @Column(name = "freeze_amount", nullable = false, precision = 36, scale = 18)
    private BigDecimal freezeAmount;

    @Column(name = "freeze_asset", nullable = false, length = 10)
    private String freezeAsset;

    @Column(name = "rejection_reason", length = 200)
    private String rejectionReason;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Factory for a brand-new order. State = NEW, filledQuantity = 0.
     * Business validation (pair support, freeze computation, balance checks)
     * is performed by the placement use case in a later phase.
     */
    public static Order newOrder(UUID userId,
                                 UUID clientOrderId,
                                 String pair,
                                 OrderSide side,
                                 OrderType type,
                                 BigDecimal quantity,
                                 BigDecimal limitPrice,
                                 String timeInForce,
                                 BigDecimal freezeAmount,
                                 String freezeAsset) {
        Order o = new Order();
        o.id = UUID.randomUUID();
        o.userId = userId;
        o.clientOrderId = clientOrderId;
        o.pair = pair;
        o.side = side;
        o.type = type;
        o.quantity = quantity;
        o.limitPrice = limitPrice;
        o.timeInForce = timeInForce != null ? timeInForce : "GTC";
        o.state = OrderState.NEW;
        o.filledQuantity = BigDecimal.ZERO;
        o.freezeAmount = freezeAmount;
        o.freezeAsset = freezeAsset;
        return o;
    }

    /**
     * Marks the order as cancel-requested. Full lifecycle transitions and
     * guards are fleshed out in a later phase.
     */
    public void markCancelRequested() {
        this.state = OrderState.CANCEL_REQUESTED;
    }

    @PrePersist
    private void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    private void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
