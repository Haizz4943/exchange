package com.haizz.exchange.matching.application;

import com.haizz.exchange.common.enums.OrderSide;
import com.haizz.exchange.matching.config.AppProperties;
import com.haizz.exchange.matching.domain.FeedStatusRegistry;
import com.haizz.exchange.matching.domain.ResidentOrder;
import com.haizz.exchange.matching.infrastructure.client.MarketDataClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Real implementation of {@link MarketOrderHook} (SR-051, SR-052, SR-059).
 *
 * <p>A market order executes immediately by walking the Binance depth snapshot and
 * accumulating fills at each level (best → worst) with a fixed slippage applied to the
 * level price (VWAP across fills). Market orders never rest in the index.
 *
 * <p><b>Threading:</b> {@link #handle} runs on the pair's dedicated executor thread (the
 * dispatcher submits it there), so the walk is single-threaded per pair.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketOrderMatcher implements MarketOrderHook {

    /** Depth levels to request from Market Data. */
    private static final int DEPTH = 20;
    /** Scale for the slippage-adjusted fill price. */
    private static final int PRICE_SCALE = 8;

    private final FeedStatusRegistry feedStatusRegistry;
    private final MarketDataClient marketDataClient;
    private final FillEmitter fillEmitter;
    private final AppProperties appProperties;

    @Override
    public void handle(ResidentOrder mo) {
        String pair = mo.getPair();
        OrderSide side = mo.getSide();

        // 1. Reject if the external feed is not trustworthy.
        if (!feedStatusRegistry.isTradeable(pair)) {
            log.warn("Rejecting MARKET order orderId={} pair={} — feed not tradeable (status={})",
                    mo.getOrderId(), pair, feedStatusRegistry.statusOf(pair));
            fillEmitter.emitRejected(mo.getOrderId(), mo.getUserId(), pair, "REJECTED");
            return;
        }

        // 2. Fetch depth. BUY walks asks ascending; SELL walks bids descending.
        MarketDataClient.DepthResponse depth;
        try {
            depth = marketDataClient.getDepth(pair, DEPTH);
        } catch (Exception e) {
            log.warn("Rejecting MARKET order orderId={} pair={} — depth fetch failed: {}",
                    mo.getOrderId(), pair, e.toString());
            fillEmitter.emitRejected(mo.getOrderId(), mo.getUserId(), pair, "REJECTED");
            return;
        }

        List<List<String>> levels = side == OrderSide.BUY
                ? (depth == null ? null : depth.asks())
                : (depth == null ? null : depth.bids());
        if (levels == null || levels.isEmpty()) {
            log.warn("Rejecting MARKET order orderId={} pair={} — empty depth", mo.getOrderId(), pair);
            fillEmitter.emitRejected(mo.getOrderId(), mo.getUserId(), pair, "REJECTED");
            return;
        }

        // Bids are descending and asks ascending in the depth response; both already
        // ordered best → worst for the respective side.
        BigDecimal slippage = appProperties.matching().marketSlippage();
        BigDecimal remaining = mo.getTotalQuantity();
        List<Fill> fills = new ArrayList<>();

        for (List<String> level : levels) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            if (level.size() < 2) {
                continue;
            }
            BigDecimal levelPrice = new BigDecimal(level.get(0));
            BigDecimal levelQty = new BigDecimal(level.get(1));
            if (levelQty.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            BigDecimal fillQty = remaining.min(levelQty);
            BigDecimal slippageFactor = side == OrderSide.BUY
                    ? BigDecimal.ONE.add(slippage)
                    : BigDecimal.ONE.subtract(slippage);
            BigDecimal fillPrice = levelPrice.multiply(slippageFactor)
                    .setScale(PRICE_SCALE, RoundingMode.HALF_UP);

            fills.add(new Fill(fillQty, fillPrice, false, null));
            remaining = remaining.subtract(fillQty);
        }

        if (fills.isEmpty()) {
            log.warn("Rejecting MARKET order orderId={} pair={} — no fillable depth", mo.getOrderId(), pair);
            fillEmitter.emitRejected(mo.getOrderId(), mo.getUserId(), pair, "REJECTED");
            return;
        }

        boolean fullyFilled = remaining.compareTo(BigDecimal.ZERO) <= 0;
        // Mark the last fill as the final fill in all completed-walk cases (fully filled
        // OR depth-exhausted partial that we auto-cancel).
        markLastFillFinal(fills);

        fillEmitter.emitOrderFills(mo.getOrderId(), mo.getUserId(), pair, side, fills,
                BigDecimal.ZERO, mo.getTotalQuantity(), fullyFilled, !fullyFilled);

        log.info("MARKET order executed orderId={} pair={} side={} fills={} fullyFilled={}",
                mo.getOrderId(), pair, side, fills.size(), fullyFilled);
    }

    private void markLastFillFinal(List<Fill> fills) {
        int last = fills.size() - 1;
        Fill f = fills.get(last);
        fills.set(last, new Fill(f.quantity(), f.price(), true, f.externalTradeId()));
    }
}
