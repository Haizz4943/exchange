package com.haizz.exchange.order.domain;

import com.haizz.exchange.common.enums.OrderType;
import com.haizz.exchange.order.domain.exception.InvalidOrderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for order admission validation (SR-033).
 */
class OrderValidatorTest {

    private static final BigDecimal STEP = new BigDecimal("0.0001");
    private static final BigDecimal TICK = new BigDecimal("0.01");
    private static final BigDecimal MIN_NOTIONAL = new BigDecimal("10");

    // --- price rules -------------------------------------------------------

    @Test
    @DisplayName("LIMIT without price → LIMIT_PRICE_REQUIRED")
    void limitRequiresPrice() {
        InvalidOrderException ex = assertThrows(InvalidOrderException.class,
                () -> OrderValidator.validatePriceRules(OrderType.LIMIT, null));
        assertEquals("LIMIT_PRICE_REQUIRED", ex.getErrorCode());
    }

    @Test
    @DisplayName("MARKET with price → LIMIT_PRICE_NOT_ALLOWED")
    void marketRejectsPrice() {
        InvalidOrderException ex = assertThrows(InvalidOrderException.class,
                () -> OrderValidator.validatePriceRules(OrderType.MARKET, new BigDecimal("1")));
        assertEquals("LIMIT_PRICE_NOT_ALLOWED", ex.getErrorCode());
    }

    @Test
    @DisplayName("valid price rules pass")
    void priceRulesPass() {
        assertDoesNotThrow(() -> OrderValidator.validatePriceRules(OrderType.LIMIT, new BigDecimal("1")));
        assertDoesNotThrow(() -> OrderValidator.validatePriceRules(OrderType.MARKET, null));
    }

    // --- quantity / step ---------------------------------------------------

    @Test
    @DisplayName("quantity not a step multiple → INVALID_QUANTITY")
    void quantityNotStepMultiple() {
        InvalidOrderException ex = assertThrows(InvalidOrderException.class,
                () -> OrderValidator.validateQuantity(new BigDecimal("0.00015"), STEP));
        assertEquals("INVALID_QUANTITY", ex.getErrorCode());
    }

    @Test
    @DisplayName("non-positive quantity → INVALID_QUANTITY")
    void quantityNonPositive() {
        assertThrows(InvalidOrderException.class,
                () -> OrderValidator.validateQuantity(BigDecimal.ZERO, STEP));
        assertThrows(InvalidOrderException.class,
                () -> OrderValidator.validateQuantity(new BigDecimal("-0.1"), STEP));
    }

    @Test
    @DisplayName("valid step-aligned quantity passes")
    void quantityValid() {
        assertDoesNotThrow(() -> OrderValidator.validateQuantity(new BigDecimal("0.1000"), STEP));
    }

    // --- limit price / tick ------------------------------------------------

    @Test
    @DisplayName("LIMIT price not a tick multiple → INVALID_PRICE")
    void priceNotTickMultiple() {
        InvalidOrderException ex = assertThrows(InvalidOrderException.class,
                () -> OrderValidator.validateLimitPrice(OrderType.LIMIT, new BigDecimal("55000.005"), TICK));
        assertEquals("INVALID_PRICE", ex.getErrorCode());
    }

    @Test
    @DisplayName("LIMIT tick-aligned price passes; MARKET skips tick check")
    void priceValid() {
        assertDoesNotThrow(() -> OrderValidator.validateLimitPrice(OrderType.LIMIT, new BigDecimal("55000.00"), TICK));
        assertDoesNotThrow(() -> OrderValidator.validateLimitPrice(OrderType.MARKET, null, TICK));
    }

    // --- min notional ------------------------------------------------------

    @Test
    @DisplayName("notional below minNotional → BELOW_MIN_NOTIONAL")
    void belowMinNotional() {
        // 0.0001 * 55000 = 5.5 < 10
        InvalidOrderException ex = assertThrows(InvalidOrderException.class,
                () -> OrderValidator.validateMinNotional(new BigDecimal("55000"), new BigDecimal("0.0001"), MIN_NOTIONAL));
        assertEquals("BELOW_MIN_NOTIONAL", ex.getErrorCode());
    }

    @Test
    @DisplayName("notional at/above minNotional passes")
    void atMinNotional() {
        assertDoesNotThrow(() -> OrderValidator.validateMinNotional(new BigDecimal("55000"), new BigDecimal("0.001"), MIN_NOTIONAL));
    }

    // --- isMultipleOf primitive -------------------------------------------

    @Test
    @DisplayName("isMultipleOf handles null/zero step as always-true")
    void isMultipleOfEdges() {
        assertTrue(OrderValidator.isMultipleOf(new BigDecimal("0.123"), null));
        assertTrue(OrderValidator.isMultipleOf(new BigDecimal("0.123"), BigDecimal.ZERO));
        assertTrue(OrderValidator.isMultipleOf(new BigDecimal("0.0002"), STEP));
        assertFalse(OrderValidator.isMultipleOf(new BigDecimal("0.00025"), STEP));
    }
}
