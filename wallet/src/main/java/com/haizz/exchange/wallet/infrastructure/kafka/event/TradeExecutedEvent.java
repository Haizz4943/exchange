package com.haizz.exchange.wallet.infrastructure.kafka.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published by Matching Engine, consumed by Wallet Service.
 * Extended with isFinalFill + residualFrozenAmount per SRS_Appendix_WalletService §3.5.4.
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
        String role,          // MAKER or TAKER
        Instant executedAt,
        boolean isFinalFill,
        BigDecimal residualFrozenAmount,
        String residualAsset
) {}
