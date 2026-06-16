package com.haizz.exchange.matching.application;

import com.haizz.exchange.common.enums.OrderSide;
import com.haizz.exchange.common.enums.OrderType;
import com.haizz.exchange.matching.domain.FeedStatusRegistry;
import com.haizz.exchange.matching.domain.OpenOrdersIndex;
import com.haizz.exchange.matching.domain.ResidentOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link LimitOrderMatcher}: FIFO distribution of external volume,
 * better-of fill price, index removal on full fill, degraded-feed skip. Collaborators mocked.
 */
@ExtendWith(MockitoExtension.class)
class LimitOrderMatcherTest {

    private static final String PAIR = "BTCUSDT";

    @Mock
    private FeedStatusRegistry feedStatusRegistry;
    @Mock
    private OpenOrdersIndex openOrdersIndex;
    @Mock
    private FillEmitter fillEmitter;

    private LimitOrderMatcher matcher;

    @BeforeEach
    void setUp() {
        matcher = new LimitOrderMatcher(feedStatusRegistry, openOrdersIndex, fillEmitter);
    }

    private static ResidentOrder limit(OrderSide side, String limitPrice, String total, String filled) {
        return new ResidentOrder(UUID.randomUUID(), UUID.randomUUID(), PAIR, side, OrderType.LIMIT,
                new BigDecimal(total), new BigDecimal(limitPrice), Instant.now(),
                filled == null ? BigDecimal.ZERO : new BigDecimal(filled));
    }

    @SuppressWarnings("unchecked")
    private List<Fill> fillsFor(UUID orderId) {
        ArgumentCaptor<List<Fill>> captor = ArgumentCaptor.forClass(List.class);
        verify(fillEmitter).emitOrderFills(eq(orderId), any(), eq(PAIR), any(), captor.capture(),
                any(), any(), anyBoolean(), anyBoolean());
        return captor.getValue();
    }

    @Test
    void degradedFeed_skipsMatchingEntirely() {
        when(feedStatusRegistry.isTradeable(PAIR)).thenReturn(false);
        when(feedStatusRegistry.statusOf(PAIR)).thenReturn(FeedStatusRegistry.FeedStatus.DEGRADED);

        ResidentOrder o = limit(OrderSide.BUY, "60000", "1", "0");
        matcher.onMatch(PAIR, new BigDecimal("60000"), new BigDecimal("1"), false, List.of(o));

        verifyNoInteractions(fillEmitter, openOrdersIndex);
    }

    @Test
    void buy_fillPriceIsMinOfLimitAndExternal() {
        when(feedStatusRegistry.isTradeable(PAIR)).thenReturn(true);
        // limit 60010, external 60000 -> buyer pays min = 60000
        ResidentOrder o = limit(OrderSide.BUY, "60010", "1", "0");
        matcher.onMatch(PAIR, new BigDecimal("60000"), new BigDecimal("1"), false, List.of(o));

        Fill fill = fillsFor(o.getOrderId()).get(0);
        assertThat(fill.price()).isEqualByComparingTo("60000");
        assertThat(fill.quantity()).isEqualByComparingTo("1");
    }

    @Test
    void buy_fillPriceCappedAtLimitWhenExternalHigher() {
        when(feedStatusRegistry.isTradeable(PAIR)).thenReturn(true);
        // limit 60000, external 60010 -> min = 60000 (never pay above limit)
        ResidentOrder o = limit(OrderSide.BUY, "60000", "1", "0");
        matcher.onMatch(PAIR, new BigDecimal("60010"), new BigDecimal("1"), false, List.of(o));

        assertThat(fillsFor(o.getOrderId()).get(0).price()).isEqualByComparingTo("60000");
    }

    @Test
    void sell_fillPriceIsMaxOfLimitAndExternal() {
        when(feedStatusRegistry.isTradeable(PAIR)).thenReturn(true);
        // limit 59990, external 60000 -> seller gets max = 60000
        ResidentOrder o = limit(OrderSide.SELL, "59990", "1", "0");
        matcher.onMatch(PAIR, new BigDecimal("60000"), new BigDecimal("1"), true, List.of(o));

        assertThat(fillsFor(o.getOrderId()).get(0).price()).isEqualByComparingTo("60000");
    }

    @Test
    void sell_fillPriceFlooredAtLimitWhenExternalLower() {
        when(feedStatusRegistry.isTradeable(PAIR)).thenReturn(true);
        // limit 60000, external 59990 -> max = 60000 (never sell below limit)
        ResidentOrder o = limit(OrderSide.SELL, "60000", "1", "0");
        matcher.onMatch(PAIR, new BigDecimal("59990"), new BigDecimal("1"), true, List.of(o));

        assertThat(fillsFor(o.getOrderId()).get(0).price()).isEqualByComparingTo("60000");
    }

    @Test
    void fullyFilledOrder_removedFromIndex_andMarkedFinal() {
        when(feedStatusRegistry.isTradeable(PAIR)).thenReturn(true);
        ResidentOrder o = limit(OrderSide.BUY, "60000", "1", "0");
        matcher.onMatch(PAIR, new BigDecimal("60000"), new BigDecimal("1"), false, List.of(o));

        verify(openOrdersIndex).remove(o.getOrderId());
        Fill fill = fillsFor(o.getOrderId()).get(0);
        assertThat(fill.isFinalFill()).isTrue();
        // terminalFilled flag true.
        verify(fillEmitter).emitOrderFills(eq(o.getOrderId()), any(), eq(PAIR), eq(OrderSide.BUY),
                any(), eq(BigDecimal.ZERO), eq(new BigDecimal("1")), eq(true), eq(false));
    }

    @Test
    void partiallyFilledOrder_staysInIndex_notFinal() {
        when(feedStatusRegistry.isTradeable(PAIR)).thenReturn(true);
        ResidentOrder o = limit(OrderSide.BUY, "60000", "2", "0");
        // External volume only 1 of the 2 needed.
        matcher.onMatch(PAIR, new BigDecimal("60000"), new BigDecimal("1"), false, List.of(o));

        verify(openOrdersIndex, never()).remove(any());
        Fill fill = fillsFor(o.getOrderId()).get(0);
        assertThat(fill.quantity()).isEqualByComparingTo("1");
        assertThat(fill.isFinalFill()).isFalse();
        verify(fillEmitter).emitOrderFills(eq(o.getOrderId()), any(), eq(PAIR), eq(OrderSide.BUY),
                any(), eq(BigDecimal.ZERO), eq(new BigDecimal("2")), eq(false), eq(false));
    }

    @Test
    void externalVolumeDistributedFifo_acrossMultipleOrders_untilExhausted() {
        when(feedStatusRegistry.isTradeable(PAIR)).thenReturn(true);
        ResidentOrder first = limit(OrderSide.BUY, "60000", "1", "0");
        ResidentOrder second = limit(OrderSide.BUY, "60000", "1", "0");
        ResidentOrder third = limit(OrderSide.BUY, "60000", "1", "0");

        // External volume 1.5 across three 1.0 orders: first full, second 0.5, third untouched.
        matcher.onMatch(PAIR, new BigDecimal("60000"), new BigDecimal("1.5"), false,
                List.of(first, second, third));

        assertThat(fillsFor(first.getOrderId()).get(0).quantity()).isEqualByComparingTo("1");
        assertThat(fillsFor(second.getOrderId()).get(0).quantity()).isEqualByComparingTo("0.5");
        // Third never filled.
        verify(fillEmitter, never()).emitOrderFills(eq(third.getOrderId()), any(), any(), any(),
                any(), any(), any(), anyBoolean(), anyBoolean());
        // first fully filled removed, second not.
        verify(openOrdersIndex).remove(first.getOrderId());
        verify(openOrdersIndex, never()).remove(second.getOrderId());
        verify(openOrdersIndex, never()).remove(third.getOrderId());
        // Two emits total.
        verify(fillEmitter, times(2)).emitOrderFills(any(), any(), any(), any(), any(),
                any(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    void skipsOrdersWithNoRemainingQuantity() {
        when(feedStatusRegistry.isTradeable(PAIR)).thenReturn(true);
        ResidentOrder already = limit(OrderSide.BUY, "60000", "1", "1"); // fully filled already
        ResidentOrder fresh = limit(OrderSide.BUY, "60000", "1", "0");

        matcher.onMatch(PAIR, new BigDecimal("60000"), new BigDecimal("1"), false,
                List.of(already, fresh));

        verify(fillEmitter, never()).emitOrderFills(eq(already.getOrderId()), any(), any(), any(),
                any(), any(), any(), anyBoolean(), anyBoolean());
        assertThat(fillsFor(fresh.getOrderId()).get(0).quantity()).isEqualByComparingTo("1");
    }
}
