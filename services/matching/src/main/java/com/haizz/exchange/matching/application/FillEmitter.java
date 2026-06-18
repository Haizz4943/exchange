package com.haizz.exchange.matching.application;

import com.haizz.exchange.common.enums.OrderSide;
import com.haizz.exchange.common.enums.TradeRole;
import com.haizz.exchange.common.event.order.OrderCancelledEvent;
import com.haizz.exchange.common.event.order.OrderFilledEvent;
import com.haizz.exchange.common.event.order.OrderPartiallyFilledEvent;
import com.haizz.exchange.matching.config.AppProperties;
import com.haizz.exchange.matching.domain.Trade;
import com.haizz.exchange.matching.infrastructure.client.MarketDataClient;
import com.haizz.exchange.matching.infrastructure.kafka.event.TradeExecutedEvent;
import com.haizz.exchange.matching.infrastructure.outbox.MatchingOutboxPublisher;
import com.haizz.exchange.matching.infrastructure.persistence.TradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Transactional persister for a single order's batch of fills.
 *
 * <p>Every public method here runs in ONE transaction so that the {@link Trade} rows, the
 * {@code trade.executed} events, and the order lifecycle event ({@code OrderFilled} /
 * {@code OrderPartiallyFilled} / {@code OrderCancelled}) are committed atomically with the
 * outbox — either all of them land or none do. The matchers orchestrate (walk the book /
 * distribute FIFO) and then hand a completed batch here.
 *
 * <p><b>Roles & fees</b> (all fills are TAKER for matching, MVP):
 * <ul>
 *   <li>BUY  → feeAmount = quantity × takerRate, feeAsset = baseAsset.</li>
 *   <li>SELL → feeAmount = quantity × price × takerRate, feeAsset = quoteAsset.</li>
 * </ul>
 *
 * <p>{@code quoteQuantity = quantity × fillPrice}. {@code residualFrozenAmount} is always
 * ZERO and {@code residualAsset} always {@code null} — matching does not own freeze.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FillEmitter {

    /** Scale for trade price and quote amounts. */
    private static final int PRICE_SCALE = 8;
    /** Scale for VWAP / average fill price. */
    private static final int AVG_SCALE = 18;

    private final TradeRepository tradeRepository;
    private final MatchingOutboxPublisher outboxPublisher;
    private final MarketDataClient marketDataClient;
    private final AppProperties appProperties;

    /**
     * Persist + emit all fills for a single order, then emit its lifecycle event.
     *
     * @param orderId         the order being filled
     * @param userId          the owning user
     * @param pair            trading pair
     * @param side            order side
     * @param fills           fills for this order (non-empty)
     * @param cumulativeFilledBefore the order's filled quantity BEFORE this batch
     * @param totalQuantity   the order's total quantity
     * @param terminalFilled  true → order is fully filled after this batch (emit OrderFilled)
     * @param marketPartial   true → market order with leftover depth-exhausted remainder
     *                        (emit OrderCancelled reason=MARKET_PARTIAL after the fills)
     */
    @Transactional
    public void emitOrderFills(UUID orderId, UUID userId, String pair, OrderSide side,
                               List<Fill> fills, BigDecimal cumulativeFilledBefore,
                               BigDecimal totalQuantity, boolean terminalFilled,
                               boolean marketPartial) {
        MarketDataClient.PairMetadataResponse meta = marketDataClient.getPairMetadata(pair);
        String baseAsset = meta.baseAsset();
        String quoteAsset = meta.quoteAsset();

        BigDecimal cumulativeFilled = cumulativeFilledBefore;
        BigDecimal weightedNotional = BigDecimal.ZERO; // Σ qty×price across THIS batch
        BigDecimal batchQty = BigDecimal.ZERO;

        for (Fill fill : fills) {
            Instant executedAt = Instant.now();
            BigDecimal qty = fill.quantity();
            BigDecimal price = fill.price();
            BigDecimal quoteQuantity = qty.multiply(price).setScale(PRICE_SCALE, RoundingMode.HALF_UP);

            BigDecimal takerRate = appProperties.fees().takerRate();
            BigDecimal feeAmount;
            String feeAsset;
            if (side == OrderSide.BUY) {
                feeAmount = qty.multiply(takerRate).setScale(PRICE_SCALE, RoundingMode.HALF_UP);
                feeAsset = baseAsset;
            } else {
                feeAmount = qty.multiply(price).multiply(takerRate)
                        .setScale(PRICE_SCALE, RoundingMode.HALF_UP);
                feeAsset = quoteAsset;
            }

            Trade trade = Trade.of(orderId, userId, pair, baseAsset, quoteAsset, side,
                    price, qty, quoteQuantity, feeAmount, feeAsset, TradeRole.TAKER,
                    fill.externalTradeId(), executedAt);
            tradeRepository.save(trade);

            TradeExecutedEvent tradeEvent = new TradeExecutedEvent(
                    trade.getId(), orderId, userId, pair, baseAsset, quoteAsset,
                    side.name(), price, qty, quoteQuantity, feeAmount, feeAsset,
                    TradeRole.TAKER.name(), executedAt, fill.isFinalFill(),
                    BigDecimal.ZERO, null);
            outboxPublisher.enqueue("TradeExecuted",
                    appProperties.kafka().tradeExecutedTopic(), orderId.toString(), tradeEvent);

            cumulativeFilled = cumulativeFilled.add(qty);
            weightedNotional = weightedNotional.add(qty.multiply(price));
            batchQty = batchQty.add(qty);

            log.info("Fill persisted orderId={} pair={} side={} qty={} price={} fee={} {} final={}",
                    orderId, pair, side, qty, price, feeAmount, feeAsset, fill.isFinalFill());
        }

        Instant now = Instant.now();
        if (terminalFilled) {
            BigDecimal avgPrice = weightedNotional.compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.ZERO
                    : weightedNotional.divide(batchQty, AVG_SCALE, RoundingMode.HALF_UP);
            outboxPublisher.enqueue("OrderFilled", appProperties.kafka().matchingEventsTopic(),
                    orderId.toString(),
                    new OrderFilledEvent(orderId, userId, pair, cumulativeFilled, avgPrice, now));
        } else {
            BigDecimal remaining = totalQuantity.subtract(cumulativeFilled);
            BigDecimal lastPrice = fills.get(fills.size() - 1).price();
            outboxPublisher.enqueue("OrderPartiallyFilled", appProperties.kafka().matchingEventsTopic(),
                    orderId.toString(),
                    new OrderPartiallyFilledEvent(orderId, userId, pair, cumulativeFilled,
                            remaining, lastPrice, now));
            if (marketPartial) {
                outboxPublisher.enqueue("OrderCancelled", appProperties.kafka().matchingEventsTopic(),
                        orderId.toString(),
                        new OrderCancelledEvent(orderId, userId, pair, "MARKET_PARTIAL", now));
            }
        }
    }

    /**
     * Emit a terminal {@code OrderCancelled} with no fills (market order rejected because
     * the feed is degraded or depth is empty). Transactional for outbox atomicity.
     */
    @Transactional
    public void emitRejected(UUID orderId, UUID userId, String pair, String reason) {
        outboxPublisher.enqueue("OrderCancelled", appProperties.kafka().matchingEventsTopic(),
                orderId.toString(),
                new OrderCancelledEvent(orderId, userId, pair, reason, Instant.now()));
        log.info("Market order rejected orderId={} pair={} reason={}", orderId, pair, reason);
    }

    /**
     * Confirm a user-initiated cancellation back to the Order service so it can
     * finalize {@code CANCEL_REQUESTED → CANCELLED}. Emitted after the resting
     * order is removed from the in-memory index. The Order service owns the freeze
     * release for user cancels (its DELETE path), so this is a state-only ACK —
     * reason {@code "USER_CANCELLED"} lets the consumer skip a residual release.
     */
    @Transactional
    public void emitCancelConfirmed(UUID orderId, UUID userId, String pair) {
        outboxPublisher.enqueue("OrderCancelled", appProperties.kafka().matchingEventsTopic(),
                orderId.toString(),
                new OrderCancelledEvent(orderId, userId, pair, "USER_CANCELLED", Instant.now()));
        log.info("Confirmed user cancellation orderId={} pair={}", orderId, pair);
    }
}
