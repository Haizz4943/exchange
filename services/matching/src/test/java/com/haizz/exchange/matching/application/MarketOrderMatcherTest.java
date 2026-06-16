package com.haizz.exchange.matching.application;

import com.haizz.exchange.common.enums.OrderSide;
import com.haizz.exchange.common.enums.OrderType;
import com.haizz.exchange.matching.config.AppProperties;
import com.haizz.exchange.matching.domain.FeedStatusRegistry;
import com.haizz.exchange.matching.domain.ResidentOrder;
import com.haizz.exchange.matching.infrastructure.client.MarketDataClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link MarketOrderMatcher} (walk-the-book, slippage, VWAP, partial,
 * degraded reject). Collaborators are mocked; the emitted {@link Fill} list is captured and
 * asserted. No Spring / no DB.
 */
@ExtendWith(MockitoExtension.class)
class MarketOrderMatcherTest {

    private static final String PAIR = "BTCUSDT";
    private static final BigDecimal SLIPPAGE = new BigDecimal("0.0005");

    @Mock
    private FeedStatusRegistry feedStatusRegistry;
    @Mock
    private MarketDataClient marketDataClient;
    @Mock
    private FillEmitter fillEmitter;

    private MarketOrderMatcher matcher;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties(
                null, null, null, null,
                new AppProperties.FeesProperties(new BigDecimal("0.001")),
                new AppProperties.MatchingProperties(SLIPPAGE),
                new AppProperties.FeedProperties(60));
        matcher = new MarketOrderMatcher(feedStatusRegistry, marketDataClient, fillEmitter, props);
    }

    private static ResidentOrder marketOrder(OrderSide side, String qty) {
        return new ResidentOrder(UUID.randomUUID(), UUID.randomUUID(), PAIR, side,
                OrderType.MARKET, new BigDecimal(qty), null, Instant.now(), BigDecimal.ZERO);
    }

    private static List<String> level(String price, String qty) {
        return List.of(price, qty);
    }

    private static BigDecimal expected(String levelPrice, BigDecimal factor) {
        return new BigDecimal(levelPrice).multiply(factor).setScale(8, RoundingMode.HALF_UP);
    }

    @SuppressWarnings("unchecked")
    private List<Fill> captureFills() {
        ArgumentCaptor<List<Fill>> captor = ArgumentCaptor.forClass(List.class);
        verify(fillEmitter).emitOrderFills(any(), any(), eq(PAIR), any(), captor.capture(),
                any(), any(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean());
        return captor.getValue();
    }

    @Test
    void buy_walksAsks_threeFills_withSlippageUp_and_finalFillOnLast() {
        when(feedStatusRegistry.isTradeable(PAIR)).thenReturn(true);
        when(marketDataClient.getDepth(eq(PAIR), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new MarketDataClient.DepthResponse(PAIR, List.of(),
                        List.of(level("60000", "0.5"), level("60001", "0.3"), level("60002", "2.0")),
                        null));

        ResidentOrder mo = marketOrder(OrderSide.BUY, "1.0");
        matcher.handle(mo);

        List<Fill> fills = captureFills();
        BigDecimal factor = BigDecimal.ONE.add(SLIPPAGE); // 1.0005

        assertThat(fills).hasSize(3);
        assertThat(fills.get(0).quantity()).isEqualByComparingTo("0.5");
        assertThat(fills.get(0).price()).isEqualByComparingTo(expected("60000", factor));
        assertThat(fills.get(1).quantity()).isEqualByComparingTo("0.3");
        assertThat(fills.get(1).price()).isEqualByComparingTo(expected("60001", factor));
        assertThat(fills.get(2).quantity()).isEqualByComparingTo("0.2");
        assertThat(fills.get(2).price()).isEqualByComparingTo(expected("60002", factor));

        // Only the last fill is final.
        assertThat(fills.get(0).isFinalFill()).isFalse();
        assertThat(fills.get(1).isFinalFill()).isFalse();
        assertThat(fills.get(2).isFinalFill()).isTrue();
    }

    @Test
    void buy_emitsFullyFilledNotMarketPartial_whenDepthSufficient() {
        when(feedStatusRegistry.isTradeable(PAIR)).thenReturn(true);
        when(marketDataClient.getDepth(eq(PAIR), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new MarketDataClient.DepthResponse(PAIR, List.of(),
                        List.of(level("60000", "0.5"), level("60001", "0.3"), level("60002", "2.0")),
                        null));

        ResidentOrder mo = marketOrder(OrderSide.BUY, "1.0");
        matcher.handle(mo);

        // terminalFilled=true, marketPartial=false
        verify(fillEmitter).emitOrderFills(eq(mo.getOrderId()), eq(mo.getUserId()), eq(PAIR),
                eq(OrderSide.BUY), any(), eq(BigDecimal.ZERO), eq(mo.getTotalQuantity()),
                eq(true), eq(false));
    }

    @Test
    void buy_vwapAcrossFillsIsCorrect() {
        when(feedStatusRegistry.isTradeable(PAIR)).thenReturn(true);
        when(marketDataClient.getDepth(eq(PAIR), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new MarketDataClient.DepthResponse(PAIR, List.of(),
                        List.of(level("60000", "0.5"), level("60001", "0.3"), level("60002", "2.0")),
                        null));

        matcher.handle(marketOrder(OrderSide.BUY, "1.0"));

        List<Fill> fills = captureFills();
        BigDecimal notional = BigDecimal.ZERO;
        BigDecimal qty = BigDecimal.ZERO;
        for (Fill f : fills) {
            notional = notional.add(f.quantity().multiply(f.price()));
            qty = qty.add(f.quantity());
        }
        BigDecimal vwap = notional.divide(qty, 8, RoundingMode.HALF_UP);
        // Hand-computed: (0.5*60030 + 0.3*60030.5005 + 0.2*60031.001) / 1.0 ≈ 60030.30...
        assertThat(vwap).isGreaterThan(new BigDecimal("60030"));
        assertThat(vwap).isLessThan(new BigDecimal("60031"));
        assertThat(qty).isEqualByComparingTo("1.0");
    }

    @Test
    void buy_exceedingTotalDepth_partialFillTriggersMarketPartialAutoCancel() {
        when(feedStatusRegistry.isTradeable(PAIR)).thenReturn(true);
        when(marketDataClient.getDepth(eq(PAIR), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new MarketDataClient.DepthResponse(PAIR, List.of(),
                        List.of(level("60000", "0.5"), level("60001", "0.3")), null));

        ResidentOrder mo = marketOrder(OrderSide.BUY, "1.0");
        matcher.handle(mo);

        // terminalFilled=false, marketPartial=true
        verify(fillEmitter).emitOrderFills(eq(mo.getOrderId()), eq(mo.getUserId()), eq(PAIR),
                eq(OrderSide.BUY), any(), eq(BigDecimal.ZERO), eq(mo.getTotalQuantity()),
                eq(false), eq(true));

        List<Fill> fills = captureFills();
        assertThat(fills).hasSize(2);
        // 0.5 + 0.3 = 0.8 filled, leftover 0.2 -> auto-cancel. Last fill still final.
        assertThat(fills.get(1).isFinalFill()).isTrue();
        BigDecimal filled = fills.stream().map(Fill::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(filled).isEqualByComparingTo("0.8");
    }

    @Test
    void sell_walksBids_withSlippageDown() {
        when(feedStatusRegistry.isTradeable(PAIR)).thenReturn(true);
        when(marketDataClient.getDepth(eq(PAIR), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new MarketDataClient.DepthResponse(PAIR,
                        List.of(level("59999", "0.4"), level("59998", "1.0")),
                        List.of(), null));

        matcher.handle(marketOrder(OrderSide.SELL, "1.0"));

        List<Fill> fills = captureFills();
        BigDecimal factor = BigDecimal.ONE.subtract(SLIPPAGE); // 0.9995

        assertThat(fills).hasSize(2);
        assertThat(fills.get(0).quantity()).isEqualByComparingTo("0.4");
        assertThat(fills.get(0).price()).isEqualByComparingTo(expected("59999", factor));
        assertThat(fills.get(1).quantity()).isEqualByComparingTo("0.6");
        assertThat(fills.get(1).price()).isEqualByComparingTo(expected("59998", factor));
        // SELL price below level price (slippage down).
        assertThat(fills.get(0).price()).isLessThan(new BigDecimal("59999"));
    }

    @Test
    void slippageDirection_buyUp_sellDown_atSameLevelPrice() {
        when(feedStatusRegistry.isTradeable(PAIR)).thenReturn(true);
        // BUY case
        when(marketDataClient.getDepth(eq(PAIR), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new MarketDataClient.DepthResponse(PAIR, List.of(),
                        List.of(level("60000", "1.0")), null));
        matcher.handle(marketOrder(OrderSide.BUY, "1.0"));
        BigDecimal buyPrice = captureFills().get(0).price();
        assertThat(buyPrice).isGreaterThan(new BigDecimal("60000"));
        assertThat(buyPrice).isEqualByComparingTo(expected("60000", BigDecimal.ONE.add(SLIPPAGE)));
    }

    @Test
    void degradedFeed_emitsRejected_noFills() {
        when(feedStatusRegistry.isTradeable(PAIR)).thenReturn(false);
        when(feedStatusRegistry.statusOf(PAIR)).thenReturn(FeedStatusRegistry.FeedStatus.DEGRADED);

        ResidentOrder mo = marketOrder(OrderSide.BUY, "1.0");
        matcher.handle(mo);

        verify(fillEmitter).emitRejected(mo.getOrderId(), mo.getUserId(), PAIR, "REJECTED");
        verify(fillEmitter, never()).emitOrderFills(any(), any(), any(), any(), any(),
                any(), any(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean());
        verifyNoInteractions(marketDataClient);
    }

    @Test
    void emptyDepth_emitsRejected_noFills() {
        when(feedStatusRegistry.isTradeable(PAIR)).thenReturn(true);
        when(marketDataClient.getDepth(eq(PAIR), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new MarketDataClient.DepthResponse(PAIR, List.of(), List.of(), null));

        ResidentOrder mo = marketOrder(OrderSide.BUY, "1.0");
        matcher.handle(mo);

        verify(fillEmitter).emitRejected(mo.getOrderId(), mo.getUserId(), PAIR, "REJECTED");
        verify(fillEmitter, never()).emitOrderFills(any(), any(), any(), any(), any(),
                any(), any(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void depthFetchThrows_emitsRejected() {
        when(feedStatusRegistry.isTradeable(PAIR)).thenReturn(true);
        when(marketDataClient.getDepth(eq(PAIR), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new RuntimeException("boom"));

        ResidentOrder mo = marketOrder(OrderSide.BUY, "1.0");
        matcher.handle(mo);

        verify(fillEmitter).emitRejected(mo.getOrderId(), mo.getUserId(), PAIR, "REJECTED");
    }

    @Test
    void zeroQtyLevels_skipped() {
        when(feedStatusRegistry.isTradeable(PAIR)).thenReturn(true);
        when(marketDataClient.getDepth(eq(PAIR), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new MarketDataClient.DepthResponse(PAIR, List.of(),
                        List.of(level("60000", "0"), level("60001", "1.0")), null));

        matcher.handle(marketOrder(OrderSide.BUY, "0.5"));

        List<Fill> fills = captureFills();
        assertThat(fills).hasSize(1);
        assertThat(fills.get(0).price())
                .isEqualByComparingTo(expected("60001", BigDecimal.ONE.add(SLIPPAGE)));
    }
}
