package com.haizz.exchange.order.application;

import com.haizz.exchange.common.enums.OrderSide;
import com.haizz.exchange.common.enums.OrderType;
import com.haizz.exchange.order.application.FillPersister.FillResult;
import com.haizz.exchange.order.application.FillPersister.Outcome;
import com.haizz.exchange.order.domain.Order;
import com.haizz.exchange.order.domain.OrderState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the transactional fill persister (SR-042): cumulative→delta
 * conversion under the lock, idempotent replay skips, terminal/missing handling.
 */
@ExtendWith(MockitoExtension.class)
class FillPersisterTest {

    @Mock
    private com.haizz.exchange.order.infrastructure.persistence.OrderRepository orderRepository;
    @InjectMocks
    private FillPersister persister;

    private final UUID orderId = UUID.randomUUID();

    private Order buyOrder(String qty, String filled, OrderState state) {
        Order o = Order.newOrder(UUID.randomUUID(), null, "BTCUSDT",
                OrderSide.BUY, OrderType.LIMIT, new BigDecimal(qty), new BigDecimal("55000"),
                "GTC", new BigDecimal("5505.5"), "USDT");
        o.setId(orderId);
        o.setState(state);
        o.setFilledQuantity(new BigDecimal(filled));
        return o;
    }

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    @Test
    @DisplayName("applyPartial converts cumulative target to delta and applies it")
    void applyPartialDelta() {
        Order order = buyOrder("0.10", "0.04", OrderState.PARTIALLY_FILLED);
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Cumulative target 0.07 with current 0.04 → delta 0.03.
        FillResult result = persister.applyPartial(orderId, bd("0.07"), bd("55000"));

        assertEquals(Outcome.APPLIED, result.outcome());
        assertEquals(0, bd("0.07").compareTo(order.getFilledQuantity()));
        assertEquals(OrderState.PARTIALLY_FILLED, order.getState());
    }

    @Test
    @DisplayName("applyPartial with non-positive delta (replay) → SKIPPED, no save")
    void applyPartialIdempotentReplay() {
        Order order = buyOrder("0.10", "0.04", OrderState.PARTIALLY_FILLED);
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));

        // Cumulative target 0.04 equals current → delta 0 → skip.
        FillResult result = persister.applyPartial(orderId, bd("0.04"), bd("55000"));

        assertEquals(Outcome.SKIPPED, result.outcome());
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("applyPartial on a terminal order → SKIPPED, no save")
    void applyPartialTerminalSkip() {
        Order order = buyOrder("0.10", "0.10", OrderState.FILLED);
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));

        FillResult result = persister.applyPartial(orderId, bd("0.10"), bd("55000"));

        assertEquals(Outcome.SKIPPED, result.outcome());
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("applyPartial when order missing → MISSING")
    void applyPartialMissing() {
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.empty());

        FillResult result = persister.applyPartial(orderId, bd("0.04"), bd("55000"));

        assertEquals(Outcome.MISSING, result.outcome());
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("complete drives the order to FILLED applying the remaining delta")
    void completeFills() {
        Order order = buyOrder("0.10", "0.04", OrderState.PARTIALLY_FILLED);
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FillResult result = persister.complete(orderId, bd("0.10"), bd("55000"));

        assertEquals(Outcome.APPLIED, result.outcome());
        assertEquals(OrderState.FILLED, order.getState());
        assertEquals(0, bd("0.10").compareTo(order.getFilledQuantity()));
    }

    @Test
    @DisplayName("complete on already-FILLED order → SKIPPED (idempotent), no save")
    void completeIdempotent() {
        Order order = buyOrder("0.10", "0.10", OrderState.FILLED);
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));

        FillResult result = persister.complete(orderId, bd("0.10"), bd("55000"));

        assertEquals(Outcome.SKIPPED, result.outcome());
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("cancel transitions OPEN → CANCELLED")
    void cancelTransitions() {
        Order order = buyOrder("0.10", "0.00", OrderState.OPEN);
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FillResult result = persister.cancel(orderId);

        assertEquals(Outcome.APPLIED, result.outcome());
        assertEquals(OrderState.CANCELLED, order.getState());
    }

    @Test
    @DisplayName("cancel on a terminal order → SKIPPED, no save (guards double-release)")
    void cancelTerminalSkip() {
        Order order = buyOrder("0.10", "0.10", OrderState.FILLED);
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));

        FillResult result = persister.cancel(orderId);

        assertEquals(Outcome.SKIPPED, result.outcome());
        verify(orderRepository, never()).save(any());
    }
}
