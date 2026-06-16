package com.haizz.exchange.matching.application;

import com.haizz.exchange.common.enums.OrderSide;
import com.haizz.exchange.common.event.order.OrderCancelledEvent;
import com.haizz.exchange.common.event.order.OrderFilledEvent;
import com.haizz.exchange.common.event.order.OrderPartiallyFilledEvent;
import com.haizz.exchange.matching.config.AppProperties;
import com.haizz.exchange.matching.domain.Trade;
import com.haizz.exchange.matching.infrastructure.client.MarketDataClient;
import com.haizz.exchange.matching.infrastructure.kafka.event.TradeExecutedEvent;
import com.haizz.exchange.matching.infrastructure.outbox.MatchingOutboxPublisher;
import com.haizz.exchange.matching.infrastructure.persistence.TradeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link FillEmitter}: fee asset rules, quoteQuantity, residual fields,
 * isFinalFill, and lifecycle-event emission (OrderFilled / OrderPartiallyFilled /
 * OrderCancelled[MARKET_PARTIAL]). DB + outbox + market-data collaborators mocked; the
 * persisted Trade and enqueued events are captured and asserted.
 */
@ExtendWith(MockitoExtension.class)
class FillEmitterTest {

    private static final String PAIR = "BTCUSDT";
    private static final BigDecimal TAKER = new BigDecimal("0.001"); // 0.10%
    private static final String TRADE_TOPIC = "trade.executed";
    private static final String MATCHING_TOPIC = "matching.events.v1";

    @Mock
    private TradeRepository tradeRepository;
    @Mock
    private MatchingOutboxPublisher outboxPublisher;
    @Mock
    private MarketDataClient marketDataClient;

    private FillEmitter emitter;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties(
                null,
                new AppProperties.KafkaTopicProperties("order.events", "marketdata.events",
                        MATCHING_TOPIC, TRADE_TOPIC),
                null, null,
                new AppProperties.FeesProperties(TAKER),
                new AppProperties.MatchingProperties(new BigDecimal("0.0005")),
                new AppProperties.FeedProperties(60));
        emitter = new FillEmitter(tradeRepository, outboxPublisher, marketDataClient, props);

        // Lenient: emitRejected() never resolves metadata, so this stub is unused there.
        org.mockito.Mockito.lenient().when(marketDataClient.getPairMetadata(PAIR)).thenReturn(
                new MarketDataClient.PairMetadataResponse(PAIR, "BTC", "USDT",
                        null, null, null, "TRADING", null));
    }

    private TradeExecutedEvent captureTradeEvent() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(outboxPublisher).enqueue(eq("TradeExecuted"), eq(TRADE_TOPIC), anyString(),
                captor.capture());
        return (TradeExecutedEvent) captor.getValue();
    }

    private Trade captureTrade() {
        ArgumentCaptor<Trade> captor = ArgumentCaptor.forClass(Trade.class);
        verify(tradeRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void buy_fee_inBaseAsset_quantityTimesTakerRate() {
        UUID orderId = UUID.randomUUID();
        Fill fill = new Fill(new BigDecimal("2"), new BigDecimal("60000"), true, null);

        emitter.emitOrderFills(orderId, UUID.randomUUID(), PAIR, OrderSide.BUY, List.of(fill),
                BigDecimal.ZERO, new BigDecimal("2"), true, false);

        Trade trade = captureTrade();
        // fee = qty * takerRate = 2 * 0.001 = 0.002 in BTC
        assertThat(trade.getFeeAmount()).isEqualByComparingTo("0.002");
        assertThat(trade.getFeeAsset()).isEqualTo("BTC");
        // quoteQuantity = qty * price = 2 * 60000 = 120000
        assertThat(trade.getQuoteAmount()).isEqualByComparingTo("120000");
    }

    @Test
    void sell_fee_inQuoteAsset_quantityTimesPriceTimesTakerRate() {
        UUID orderId = UUID.randomUUID();
        Fill fill = new Fill(new BigDecimal("2"), new BigDecimal("60000"), true, null);

        emitter.emitOrderFills(orderId, UUID.randomUUID(), PAIR, OrderSide.SELL, List.of(fill),
                BigDecimal.ZERO, new BigDecimal("2"), true, false);

        Trade trade = captureTrade();
        // fee = qty * price * takerRate = 2 * 60000 * 0.001 = 120 in USDT
        assertThat(trade.getFeeAmount()).isEqualByComparingTo("120");
        assertThat(trade.getFeeAsset()).isEqualTo("USDT");
        assertThat(trade.getQuoteAmount()).isEqualByComparingTo("120000");
    }

    @Test
    void tradeEvent_alwaysTakerRole_residualZero_residualAssetNull() {
        UUID orderId = UUID.randomUUID();
        Fill fill = new Fill(new BigDecimal("1"), new BigDecimal("60000"), true, null);

        emitter.emitOrderFills(orderId, UUID.randomUUID(), PAIR, OrderSide.BUY, List.of(fill),
                BigDecimal.ZERO, new BigDecimal("1"), true, false);

        TradeExecutedEvent event = captureTradeEvent();
        assertThat(event.role()).isEqualTo("TAKER");
        assertThat(event.residualFrozenAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(event.residualAsset()).isNull();
        assertThat(event.baseAsset()).isEqualTo("BTC");
        assertThat(event.quoteAsset()).isEqualTo("USDT");
        assertThat(event.quoteQuantity()).isEqualByComparingTo("60000");
    }

    @Test
    void tradeEvent_isFinalFill_reflectsFillFlag() {
        UUID orderId = UUID.randomUUID();
        Fill finalFill = new Fill(new BigDecimal("1"), new BigDecimal("60000"), true, null);

        emitter.emitOrderFills(orderId, UUID.randomUUID(), PAIR, OrderSide.BUY, List.of(finalFill),
                BigDecimal.ZERO, new BigDecimal("1"), true, false);

        assertThat(captureTradeEvent().isFinalFill()).isTrue();
    }

    @Test
    void terminalFilled_emitsOrderFilled_withVwapAvgPrice() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        List<Fill> fills = List.of(
                new Fill(new BigDecimal("0.5"), new BigDecimal("60000"), false, null),
                new Fill(new BigDecimal("0.5"), new BigDecimal("60002"), true, null));

        emitter.emitOrderFills(orderId, userId, PAIR, OrderSide.BUY, fills,
                BigDecimal.ZERO, new BigDecimal("1.0"), true, false);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(outboxPublisher).enqueue(eq("OrderFilled"), eq(MATCHING_TOPIC), anyString(),
                captor.capture());
        OrderFilledEvent ev = (OrderFilledEvent) captor.getValue();
        assertThat(ev.orderId()).isEqualTo(orderId);
        assertThat(ev.filledQuantity()).isEqualByComparingTo("1.0");
        // VWAP = (0.5*60000 + 0.5*60002)/1.0 = 60001
        assertThat(ev.avgPrice()).isEqualByComparingTo("60001");
        verify(outboxPublisher, never()).enqueue(eq("OrderPartiallyFilled"), any(), any(), any());
    }

    @Test
    void partial_nonMarket_emitsOrderPartiallyFilled_noCancel() {
        UUID orderId = UUID.randomUUID();
        Fill fill = new Fill(new BigDecimal("0.4"), new BigDecimal("60000"), false, null);

        emitter.emitOrderFills(orderId, UUID.randomUUID(), PAIR, OrderSide.BUY, List.of(fill),
                BigDecimal.ZERO, new BigDecimal("1.0"), false, false);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(outboxPublisher).enqueue(eq("OrderPartiallyFilled"), eq(MATCHING_TOPIC), anyString(),
                captor.capture());
        OrderPartiallyFilledEvent ev = (OrderPartiallyFilledEvent) captor.getValue();
        assertThat(ev.filledQuantity()).isEqualByComparingTo("0.4");
        assertThat(ev.remainingQuantity()).isEqualByComparingTo("0.6");
        verify(outboxPublisher, never()).enqueue(eq("OrderCancelled"), any(), any(), any());
    }

    @Test
    void marketPartial_emitsPartiallyFilled_thenCancelledMarketPartial() {
        UUID orderId = UUID.randomUUID();
        Fill fill = new Fill(new BigDecimal("0.8"), new BigDecimal("60000"), true, null);

        emitter.emitOrderFills(orderId, UUID.randomUUID(), PAIR, OrderSide.BUY, List.of(fill),
                BigDecimal.ZERO, new BigDecimal("1.0"), false, true);

        verify(outboxPublisher).enqueue(eq("OrderPartiallyFilled"), eq(MATCHING_TOPIC), anyString(),
                any());
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(outboxPublisher).enqueue(eq("OrderCancelled"), eq(MATCHING_TOPIC), anyString(),
                captor.capture());
        OrderCancelledEvent ev = (OrderCancelledEvent) captor.getValue();
        assertThat(ev.reason()).isEqualTo("MARKET_PARTIAL");
    }

    @Test
    void emitRejected_enqueuesOrderCancelled_withGivenReason_noTrade() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        emitter.emitRejected(orderId, userId, PAIR, "REJECTED");

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(outboxPublisher).enqueue(eq("OrderCancelled"), eq(MATCHING_TOPIC), anyString(),
                captor.capture());
        OrderCancelledEvent ev = (OrderCancelledEvent) captor.getValue();
        assertThat(ev.reason()).isEqualTo("REJECTED");
        assertThat(ev.orderId()).isEqualTo(orderId);
        verify(tradeRepository, never()).save(any());
    }

    @Test
    void persistsTrade_withCorrectPriceQuantityAndPair() {
        UUID orderId = UUID.randomUUID();
        Fill fill = new Fill(new BigDecimal("0.25"), new BigDecimal("60001"), true, "ext-123");

        emitter.emitOrderFills(orderId, UUID.randomUUID(), PAIR, OrderSide.BUY, List.of(fill),
                BigDecimal.ZERO, new BigDecimal("0.25"), true, false);

        Trade trade = captureTrade();
        assertThat(trade.getPair()).isEqualTo(PAIR);
        assertThat(trade.getPrice()).isEqualByComparingTo("60001");
        assertThat(trade.getQuantity()).isEqualByComparingTo("0.25");
        assertThat(trade.getExternalTradeId()).isEqualTo("ext-123");
        assertThat(trade.getOrderId()).isEqualTo(orderId);
    }
}
