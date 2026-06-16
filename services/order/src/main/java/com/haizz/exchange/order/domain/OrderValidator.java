package com.haizz.exchange.order.domain;

import com.haizz.exchange.common.enums.OrderType;
import com.haizz.exchange.order.domain.exception.InvalidOrderException;

import java.math.BigDecimal;

/**
 * Pure (no-Spring) order admission validation (SR-033). Extracted from
 * {@code PlaceOrderUseCase} so each rule can be unit-tested in isolation; the
 * use case delegates here so the runtime behaviour is unchanged. Each method
 * throws {@link InvalidOrderException} (carrying the API_SPEC error code) on
 * violation and returns normally otherwise.
 */
public final class OrderValidator {

    private OrderValidator() {
    }

    /** LIMIT requires a price; MARKET must not carry one. */
    public static void validatePriceRules(OrderType type, BigDecimal limitPrice) {
        if (type == OrderType.LIMIT && limitPrice == null) {
            throw new InvalidOrderException("LIMIT_PRICE_REQUIRED",
                    "limit_price is required for LIMIT orders");
        }
        if (type == OrderType.MARKET && limitPrice != null) {
            throw new InvalidOrderException("LIMIT_PRICE_NOT_ALLOWED",
                    "limit_price must not be provided for MARKET orders");
        }
    }

    /** Quantity must be a positive integer multiple of {@code stepSize}. */
    public static void validateQuantity(BigDecimal quantity, BigDecimal stepSize) {
        if (quantity == null || quantity.signum() <= 0 || !isMultipleOf(quantity, stepSize)) {
            throw new InvalidOrderException("INVALID_QUANTITY",
                    "quantity must be a positive multiple of step_size " + stepSize);
        }
    }

    /** For LIMIT orders, price must be a positive integer multiple of {@code tickSize}. */
    public static void validateLimitPrice(OrderType type, BigDecimal limitPrice, BigDecimal tickSize) {
        if (type == OrderType.LIMIT
                && (limitPrice == null || limitPrice.signum() <= 0
                || !isMultipleOf(limitPrice, tickSize))) {
            throw new InvalidOrderException("INVALID_PRICE",
                    "limit_price must be a positive multiple of tick_size " + tickSize);
        }
    }

    /** Order notional ({@code price × quantity}) must be ≥ {@code minNotional}. */
    public static void validateMinNotional(BigDecimal notionalPrice,
                                           BigDecimal quantity,
                                           BigDecimal minNotional) {
        BigDecimal notional = notionalPrice.multiply(quantity);
        if (notional.compareTo(minNotional) < 0) {
            throw new InvalidOrderException("BELOW_MIN_NOTIONAL",
                    "Order notional " + notional.toPlainString()
                            + " is below min_notional " + minNotional);
        }
    }

    /** True if {@code value} is a non-negative integer multiple of {@code step} (step &gt; 0). */
    public static boolean isMultipleOf(BigDecimal value, BigDecimal step) {
        if (step == null || step.signum() <= 0) {
            return true;
        }
        return value.remainder(step).compareTo(BigDecimal.ZERO) == 0;
    }
}
