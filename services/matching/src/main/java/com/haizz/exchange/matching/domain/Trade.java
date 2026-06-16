package com.haizz.exchange.matching.domain;

import com.haizz.exchange.common.enums.OrderSide;
import com.haizz.exchange.common.enums.TradeRole;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "trades")
@Getter
@NoArgsConstructor
public class Trade {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "pair", nullable = false, length = 20)
    private String pair;

    @Column(name = "base_asset", nullable = false, length = 10)
    private String baseAsset;

    @Column(name = "quote_asset", nullable = false, length = 10)
    private String quoteAsset;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false, length = 4)
    private OrderSide side;

    @Column(name = "price", nullable = false, precision = 36, scale = 18)
    private BigDecimal price;

    @Column(name = "quantity", nullable = false, precision = 36, scale = 18)
    private BigDecimal quantity;

    @Column(name = "quote_amount", nullable = false, precision = 36, scale = 18)
    private BigDecimal quoteAmount;

    @Column(name = "fee_amount", nullable = false, precision = 36, scale = 18)
    private BigDecimal feeAmount;

    @Column(name = "fee_asset", nullable = false, length = 10)
    private String feeAsset;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 5)
    private TradeRole role;

    @Column(name = "external_trade_id", length = 64)
    private String externalTradeId;

    @Column(name = "executed_at", nullable = false)
    private Instant executedAt;

    public static Trade of(UUID orderId, UUID userId, String pair, String baseAsset,
                           String quoteAsset, OrderSide side, BigDecimal price,
                           BigDecimal quantity, BigDecimal quoteAmount, BigDecimal feeAmount,
                           String feeAsset, TradeRole role, String externalTradeId,
                           Instant executedAt) {
        Trade t = new Trade();
        t.id = UUID.randomUUID();
        t.orderId = orderId;
        t.userId = userId;
        t.pair = pair;
        t.baseAsset = baseAsset;
        t.quoteAsset = quoteAsset;
        t.side = side;
        t.price = price;
        t.quantity = quantity;
        t.quoteAmount = quoteAmount;
        t.feeAmount = feeAmount;
        t.feeAsset = feeAsset;
        t.role = role;
        t.externalTradeId = externalTradeId;
        t.executedAt = executedAt;
        return t;
    }

    @PrePersist
    private void onPersist() {
        if (executedAt == null) {
            executedAt = Instant.now();
        }
    }
}
