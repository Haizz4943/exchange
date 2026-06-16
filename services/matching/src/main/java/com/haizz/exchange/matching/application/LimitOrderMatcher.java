package com.haizz.exchange.matching.application;

import com.haizz.exchange.common.enums.OrderSide;
import com.haizz.exchange.matching.domain.FeedStatusRegistry;
import com.haizz.exchange.matching.domain.OpenOrdersIndex;
import com.haizz.exchange.matching.domain.ResidentOrder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Real implementation of {@link LimitMatchHook} (SR-053, SR-054).
 *
 * <p>When an external trade touches resting limit orders, the external volume is
 * distributed across the eligible orders FIFO (oldest first). Each order's fills + its
 * lifecycle event commit in their own transaction (a single external trade may touch
 * several orders; a per-order transaction each is simpler and acceptable).
 *
 * <p><b>Fill price</b> (the resting order is the better-priced side):
 * <ul>
 *   <li>BUY  → {@code min(limitPrice, externalPrice)} (buyer never pays above its limit).</li>
 *   <li>SELL → {@code max(limitPrice, externalPrice)} (seller never sells below its limit).</li>
 * </ul>
 *
 * <p><b>Threading:</b> {@link #onMatch} runs on the pair's dedicated executor thread and
 * mutates {@link OpenOrdersIndex} single-threaded per pair.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LimitOrderMatcher implements LimitMatchHook {

    private final FeedStatusRegistry feedStatusRegistry;
    private final OpenOrdersIndex openOrdersIndex;
    private final FillEmitter fillEmitter;

    @Override
    public void onMatch(String pair, BigDecimal externalPrice, BigDecimal externalQty,
                        boolean buyerIsMaker, List<ResidentOrder> eligible) {
        // 1. No matching while the feed is degraded — orders keep resting.
        if (!feedStatusRegistry.isTradeable(pair)) {
            log.warn("Skipping limit matching for pair={} — feed not tradeable (status={})",
                    pair, feedStatusRegistry.statusOf(pair));
            return;
        }

        BigDecimal remainingExternal = externalQty;
        for (ResidentOrder order : eligible) {
            if (remainingExternal.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            BigDecimal orderRemaining = order.remainingQuantity();
            if (orderRemaining.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            BigDecimal fillQty = orderRemaining.min(remainingExternal);
            BigDecimal fillPrice = order.getSide() == OrderSide.BUY
                    ? order.getLimitPrice().min(externalPrice)
                    : order.getLimitPrice().max(externalPrice);

            BigDecimal filledBefore = order.getFilledQuantity();
            order.addFill(fillQty);
            boolean fullyFilled = order.isFullyFilled();
            if (fullyFilled) {
                openOrdersIndex.remove(order.getOrderId());
            }

            Fill fill = new Fill(fillQty, fillPrice, fullyFilled, null);
            fillEmitter.emitOrderFills(order.getOrderId(), order.getUserId(), pair,
                    order.getSide(), List.of(fill), filledBefore, order.getTotalQuantity(),
                    fullyFilled, false);

            remainingExternal = remainingExternal.subtract(fillQty);

            log.info("Limit fill orderId={} pair={} side={} qty={} price={} fullyFilled={}",
                    order.getOrderId(), pair, order.getSide(), fillQty, fillPrice, fullyFilled);
        }
    }
}
