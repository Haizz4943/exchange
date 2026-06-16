package com.haizz.exchange.matching.infrastructure.kafka.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Emitted by the Matching Engine on {@code trade.executed} and consumed by the Wallet
 * Service to move balances.
 *
 * <p><b>Contract:</b> the component names here MUST match the Wallet-side record
 * ({@code services/wallet/.../infrastructure/kafka/event/TradeExecutedEvent.java})
 * verbatim, because Jackson round-trips JSON by record component name.
 *
 * <p>Matching does NOT compute placement-time freeze, so {@code residualFrozenAmount}
 * is always {@link BigDecimal#ZERO} and {@code residualAsset} is always {@code null}.
 * The Order service releases any residual freeze itself on terminal. See DECISIONS.md.
 */
public record TradeExecutedEvent(
        UUID tradeId,
        UUID orderId,
        UUID userId,
        String pair,
        String baseAsset,
        String quoteAsset,
        String side,          // BUY or SELL
        BigDecimal price,
        BigDecimal quantity,
        BigDecimal quoteQuantity,
        BigDecimal feeAmount,
        String feeAsset,
        String role,          // MAKER or TAKER (matching always TAKER)
        Instant executedAt,
        boolean isFinalFill,
        BigDecimal residualFrozenAmount,
        String residualAsset
) {}
