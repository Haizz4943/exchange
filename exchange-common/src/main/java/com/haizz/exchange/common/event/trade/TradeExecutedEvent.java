package com.haizz.exchange.common.event.trade;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TradeExecutedEvent(
        UUID tradeId,
        UUID buyOrderId,
        UUID sellOrderId,
        UUID buyUserId,
        UUID sellUserId,
        String pair,
        BigDecimal price,
        BigDecimal quantity,
        BigDecimal buyerFee,
        BigDecimal sellerFee,
        String buyerRole,
        String sellerRole,
        Instant executedAt
) {}
