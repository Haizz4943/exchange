package com.haizz.exchange.order.domain;

import com.haizz.exchange.common.enums.OrderSide;
import com.haizz.exchange.common.enums.OrderType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
     * Scale at which the running VWAP {@code avgFillPrice} is kept (SR-042).
     * Matches the {@code avg_fill_price} column precision (scale 18).
     */
    public static final int AVG_PRICE_SCALE = 18;

    /**
     * Applies a fill to this order (SR-042). Increases {@code filledQuantity} by
     * {@code fillQty} and recomputes {@code avgFillPrice} as the running VWAP:
     * <pre>((avgFillPrice × prevFilled) + fillPrice × fillQty) / (prevFilled + fillQty)</pre>
     * rounded HALF_UP at scale {@value #AVG_PRICE_SCALE}.
     *
     * <p>State transition: if the cumulative filled quantity reaches
     * {@code quantity} the order becomes {@link OrderState#FILLED}; otherwise
     * {@link OrderState#PARTIALLY_FILLED}. A fill is accepted from
     * NEW / OPEN / PARTIALLY_FILLED / CANCEL_REQUESTED.
     *
     * <p><b>Terminal precedence (SRS Appendix):</b> a fill completing the order
     * wins over a pending cancel — CANCEL_REQUESTED → FILLED is allowed here.
     *
     * @throws IllegalStateException    if the order is already terminal
     * @throws IllegalArgumentException if {@code fillQty} ≤ 0, {@code fillPrice} &lt; 0,
     *                                  or the fill would overfill (filled &gt; quantity)
     */
    public void applyFill(BigDecimal fillQty, BigDecimal fillPrice) {
        if (fillQty == null || fillQty.signum() <= 0) {
            throw new IllegalArgumentException("fillQty must be positive: " + fillQty);
        }
        if (fillPrice == null || fillPrice.signum() < 0) {
            throw new IllegalArgumentException("fillPrice must be non-negative: " + fillPrice);
        }
        if (this.state.isTerminal()) {
            throw new IllegalStateException(
                    "Cannot apply fill to a terminal order in state " + this.state);
        }

        BigDecimal prevFilled = this.filledQuantity != null
                ? this.filledQuantity : BigDecimal.ZERO;
        BigDecimal newFilled = prevFilled.add(fillQty);
        if (newFilled.compareTo(this.quantity) > 0) {
            throw new IllegalArgumentException(
                    "Fill would overfill order: filled=" + newFilled
                            + " exceeds quantity=" + this.quantity);
        }

        // Running VWAP over the cumulative filled quantity.
        BigDecimal prevAvg = this.avgFillPrice != null ? this.avgFillPrice : BigDecimal.ZERO;
        BigDecimal numerator = prevAvg.multiply(prevFilled).add(fillPrice.multiply(fillQty));
        this.avgFillPrice = numerator.divide(newFilled, AVG_PRICE_SCALE, RoundingMode.HALF_UP);
        this.filledQuantity = newFilled;

        this.state = newFilled.compareTo(this.quantity) == 0
                ? OrderState.FILLED
                : OrderState.PARTIALLY_FILLED;
    }

    /**
     * Moves a freshly-accepted order onto the book: NEW → OPEN.
     * Idempotent if already OPEN; rejects any other (terminal/filled) state.
     */
    public void markOpen() {
        if (this.state == OrderState.OPEN) {
            return;
        }
        if (this.state != OrderState.NEW) {
            throw new IllegalStateException("Cannot open order in state " + this.state);
        }
        this.state = OrderState.OPEN;
    }

    /**
     * Requests cancellation: NEW / OPEN / PARTIALLY_FILLED → CANCEL_REQUESTED.
     * @throws IllegalStateException if the order is not cancellable (terminal or
     *                               already CANCEL_REQUESTED).
     */
    public void markCancelRequested() {
        if (!this.state.isCancellable()) {
            throw new IllegalStateException(
                    "Order in state " + this.state + " is not cancellable");
        }
        this.state = OrderState.CANCEL_REQUESTED;
    }

    /**
     * Applies the terminal cancellation confirmed by the matching engine:
     * CANCEL_REQUESTED (or NEW / OPEN / PARTIALLY_FILLED) → CANCELLED.
     * @throws IllegalStateException if the order is already terminal.
     */
    public void markCancelled() {
        if (this.state.isTerminal()) {
            throw new IllegalStateException(
                    "Cannot cancel a terminal order in state " + this.state);
        }
        this.state = OrderState.CANCELLED;
    }

    /**
     * Rejects a brand-new order (e.g. validation/admission failure): NEW → REJECTED.
     * @throws IllegalStateException if the order has already left NEW.
     */
    public void markRejected(String reason) {
        if (this.state != OrderState.NEW) {
            throw new IllegalStateException(
                    "Only a NEW order can be rejected; state was " + this.state);
        }
        this.state = OrderState.REJECTED;
        this.rejectionReason = reason;
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
