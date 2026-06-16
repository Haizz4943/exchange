package com.haizz.exchange.matching.application;

import java.math.BigDecimal;

/**
 * A single fill against one order: a quantity executed at a price.
 *
 * <p>{@code isFinalFill} marks the fill that completes the order (or the last fill of a
 * market order that auto-cancels its remainder). It flows straight onto the emitted
 * {@code trade.executed} event so Wallet knows when an order is done.
 *
 * <p>{@code externalTradeId} is the originating external trade id for limit fills (used for
 * audit / dedup on the {@code Trade} row); {@code null} for market fills.
 */
public record Fill(
        BigDecimal quantity,
        BigDecimal price,
        boolean isFinalFill,
        String externalTradeId
) {}
