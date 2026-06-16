package com.haizz.exchange.order.domain;

import com.haizz.exchange.common.enums.OrderSide;
import com.haizz.exchange.common.enums.OrderType;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure (no-Spring) computation of the balance freeze required to place an order
 * (SR-034/035). Extracted from {@code PlaceOrderUseCase} so the per-branch
 * formula can be unit-tested in isolation; the use case delegates here so the
 * runtime behaviour is unchanged.
 *
 * <ul>
 *   <li>BUY LIMIT:  {@code qty × limitPrice × (1 + takerRate)} → quote asset</li>
 *   <li>BUY MARKET: {@code qty × bestAsk × (1 + slippage) × (1 + takerRate)} → quote asset</li>
 *   <li>SELL:       {@code qty} → base asset (no fee/slippage buffer)</li>
 * </ul>
 *
 * Quote-asset amounts are rounded {@link RoundingMode#UP} at {@link #QUOTE_FREEZE_SCALE}
 * so we never under-freeze. SELL freeze (= raw qty) is returned unscaled.
 */
public final class FreezeCalculator {

    /** Slippage buffer applied to a MARKET BUY freeze so we never under-freeze. */
    public static final BigDecimal MARKET_SLIPPAGE = new BigDecimal("0.0005");
    /** Scale for quote-asset freeze amounts (rounded UP). */
    public static final int QUOTE_FREEZE_SCALE = 8;

    private FreezeCalculator() {
    }

    /** Holds the computed freeze amount and the asset it is denominated in. */
    public record Freeze(BigDecimal amount, String asset) {
    }

    /**
     * Computes the freeze for an order.
     *
     * @param side       BUY or SELL
     * @param type       LIMIT or MARKET
     * @param quantity   order quantity (base asset)
     * @param limitPrice limit price (required for LIMIT; ignored for MARKET)
     * @param bestAsk    reference price for a MARKET BUY (ignored otherwise)
     * @param takerRate  taker fee rate (e.g. 0.001)
     * @param baseAsset  pair base asset code
     * @param quoteAsset pair quote asset code
     */
    public static Freeze compute(OrderSide side,
                                 OrderType type,
                                 BigDecimal quantity,
                                 BigDecimal limitPrice,
                                 BigDecimal bestAsk,
                                 BigDecimal takerRate,
                                 String baseAsset,
                                 String quoteAsset) {
        if (side == OrderSide.SELL) {
            return new Freeze(quantity, baseAsset);
        }

        BigDecimal price = type == OrderType.LIMIT ? limitPrice : bestAsk;
        BigDecimal gross = quantity.multiply(price);
        if (type == OrderType.MARKET) {
            gross = gross.multiply(BigDecimal.ONE.add(MARKET_SLIPPAGE));
        }
        gross = gross.multiply(BigDecimal.ONE.add(takerRate));
        BigDecimal amount = gross.setScale(QUOTE_FREEZE_SCALE, RoundingMode.UP);
        return new Freeze(amount, quoteAsset);
    }
}
