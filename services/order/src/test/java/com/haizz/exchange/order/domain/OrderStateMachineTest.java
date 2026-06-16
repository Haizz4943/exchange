package com.haizz.exchange.order.domain;

import com.haizz.exchange.common.enums.OrderSide;
import com.haizz.exchange.common.enums.OrderType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-domain unit tests for the order state machine (SR-042). No Spring / no DB.
 */
class OrderStateMachineTest {

    private static Order newLimitBuy(String qty) {
        return Order.newOrder(
                UUID.randomUUID(), null, "BTCUSDT",
                OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal(qty), new BigDecimal("55000"), "GTC",
                new BigDecimal("5505.5"), "USDT");
    }

    @Test
    @DisplayName("applyFill: partial then completing fill → PARTIALLY_FILLED then FILLED")
    void partialThenFull() {
        Order order = newLimitBuy("0.10");

        order.applyFill(new BigDecimal("0.04"), new BigDecimal("55000"));
        assertEquals(OrderState.PARTIALLY_FILLED, order.getState());
        assertEquals(0, new BigDecimal("0.04").compareTo(order.getFilledQuantity()));

        order.applyFill(new BigDecimal("0.06"), new BigDecimal("55000"));
        assertEquals(OrderState.FILLED, order.getState());
        assertEquals(0, new BigDecimal("0.10").compareTo(order.getFilledQuantity()));
        assertTrue(order.getState().isTerminal());
    }

    @Test
    @DisplayName("applyFill: avgFillPrice is the running VWAP across two fills")
    void vwapAcrossTwoFills() {
        Order order = newLimitBuy("0.10");

        // Fill 1: 0.04 @ 55000  → avg 55000
        order.applyFill(new BigDecimal("0.04"), new BigDecimal("55000"));
        assertEquals(0, new BigDecimal("55000").compareTo(order.getAvgFillPrice()));

        // Fill 2: 0.06 @ 56000  → VWAP = (55000*0.04 + 56000*0.06) / 0.10 = 55600
        order.applyFill(new BigDecimal("0.06"), new BigDecimal("56000"));
        assertEquals(0, new BigDecimal("55600").compareTo(order.getAvgFillPrice()));
    }

    @Test
    @DisplayName("terminal precedence: CANCEL_REQUESTED + completing fill → FILLED")
    void terminalPrecedenceFilledWins() {
        Order order = newLimitBuy("0.10");
        order.markCancelRequested();
        assertEquals(OrderState.CANCEL_REQUESTED, order.getState());

        // A fill completing the order overrides the pending cancel.
        order.applyFill(new BigDecimal("0.10"), new BigDecimal("55000"));
        assertEquals(OrderState.FILLED, order.getState());
    }

    @Test
    @DisplayName("applyFill from CANCEL_REQUESTED but not completing → PARTIALLY_FILLED")
    void cancelRequestedPartialFill() {
        Order order = newLimitBuy("0.10");
        order.markCancelRequested();

        order.applyFill(new BigDecimal("0.03"), new BigDecimal("55000"));
        assertEquals(OrderState.PARTIALLY_FILLED, order.getState());
    }

    @Test
    @DisplayName("applyFill rejected once terminal (FILLED)")
    void applyFillRejectedWhenTerminal() {
        Order order = newLimitBuy("0.10");
        order.applyFill(new BigDecimal("0.10"), new BigDecimal("55000"));
        assertEquals(OrderState.FILLED, order.getState());

        assertThrows(IllegalStateException.class,
                () -> order.applyFill(new BigDecimal("0.01"), new BigDecimal("55000")));
    }

    @Test
    @DisplayName("applyFill rejected when it would overfill quantity")
    void applyFillRejectsOverfill() {
        Order order = newLimitBuy("0.10");
        assertThrows(IllegalArgumentException.class,
                () -> order.applyFill(new BigDecimal("0.11"), new BigDecimal("55000")));
    }

    @Test
    @DisplayName("applyFill rejects non-positive quantity")
    void applyFillRejectsNonPositiveQty() {
        Order order = newLimitBuy("0.10");
        assertThrows(IllegalArgumentException.class,
                () -> order.applyFill(BigDecimal.ZERO, new BigDecimal("55000")));
    }

    @Test
    @DisplayName("markCancelRequested rejected on a terminal order")
    void markCancelRequestedRejectedOnTerminal() {
        Order order = newLimitBuy("0.10");
        order.applyFill(new BigDecimal("0.10"), new BigDecimal("55000")); // FILLED

        assertThrows(IllegalStateException.class, order::markCancelRequested);
    }

    @Test
    @DisplayName("markOpen: NEW → OPEN and is idempotent")
    void markOpenIdempotent() {
        Order order = newLimitBuy("0.10");
        order.markOpen();
        assertEquals(OrderState.OPEN, order.getState());
        order.markOpen(); // idempotent
        assertEquals(OrderState.OPEN, order.getState());
    }

    @Test
    @DisplayName("markOpen rejected from a terminal state")
    void markOpenRejectedFromTerminal() {
        Order order = newLimitBuy("0.10");
        order.applyFill(new BigDecimal("0.10"), new BigDecimal("55000"));
        assertThrows(IllegalStateException.class, order::markOpen);
    }

    @Test
    @DisplayName("markCancelled: CANCEL_REQUESTED → CANCELLED (terminal)")
    void markCancelledFromCancelRequested() {
        Order order = newLimitBuy("0.10");
        order.markCancelRequested();
        order.markCancelled();
        assertEquals(OrderState.CANCELLED, order.getState());
        assertTrue(order.getState().isTerminal());
    }

    @Test
    @DisplayName("markCancelled rejected once terminal")
    void markCancelledRejectedOnTerminal() {
        Order order = newLimitBuy("0.10");
        order.applyFill(new BigDecimal("0.10"), new BigDecimal("55000"));
        assertThrows(IllegalStateException.class, order::markCancelled);
    }

    @Test
    @DisplayName("markRejected: NEW → REJECTED, sets reason")
    void markRejectedFromNew() {
        Order order = newLimitBuy("0.10");
        order.markRejected("RISK_BLOCK");
        assertEquals(OrderState.REJECTED, order.getState());
        assertEquals("RISK_BLOCK", order.getRejectionReason());
        assertTrue(order.getState().isTerminal());
    }

    @Test
    @DisplayName("markRejected rejected once order has left NEW")
    void markRejectedRejectedAfterOpen() {
        Order order = newLimitBuy("0.10");
        order.markOpen();
        assertThrows(IllegalStateException.class, () -> order.markRejected("LATE"));
    }

    @Test
    @DisplayName("OrderState flags: isTerminal / isCancellable")
    void stateFlags() {
        assertTrue(OrderState.FILLED.isTerminal());
        assertTrue(OrderState.CANCELLED.isTerminal());
        assertTrue(OrderState.REJECTED.isTerminal());

        assertTrue(OrderState.NEW.isCancellable());
        assertTrue(OrderState.OPEN.isCancellable());
        assertTrue(OrderState.PARTIALLY_FILLED.isCancellable());

        assertTrue(!OrderState.CANCEL_REQUESTED.isCancellable());
        assertTrue(!OrderState.FILLED.isCancellable());
    }
}
