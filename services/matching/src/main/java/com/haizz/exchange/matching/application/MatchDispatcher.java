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
 * Applies observed external trades to the resting book.
 *
 * <p><b>Threading:</b> {@link #onExternalTrade} reads the {@link OpenOrdersIndex} and MUST
 * be invoked from the pair's dedicated executor thread (see {@code PairExecutorRegistry}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchDispatcher {

    private final OpenOrdersIndex openOrdersIndex;
    private final FeedStatusRegistry feedStatusRegistry;
    private final LimitMatchHook limitMatchHook;

    /**
     * An external trade was observed. Resting limit orders on the side aggressed by the
     * external trade become eligible to fill. Phase 2 only selects eligible orders and
     * hands them to the (stubbed) {@link LimitMatchHook}; phase 3 performs the fills.
     *
     * <p>The aggressing side: if the external buyer is the maker, then the external taker
     * was a SELLER hitting bids, so OUR resting BUY (bid) limit orders are the candidates.
     * Otherwise the external taker was a BUYER lifting asks, so OUR resting SELL (ask)
     * limit orders are the candidates.
     */
    public void onExternalTrade(String pair, BigDecimal price, BigDecimal qty, boolean buyerIsMaker) {
        if (!feedStatusRegistry.isTradeable(pair)) {
            log.warn("Skipping external trade for pair={} — feed not tradeable (status={})",
                    pair, feedStatusRegistry.statusOf(pair));
            return;
        }

        OrderSide candidateSide = buyerIsMaker ? OrderSide.BUY : OrderSide.SELL;
        List<ResidentOrder> eligible = openOrdersIndex.eligibleLimitOrders(pair, candidateSide, price);

        log.debug("External trade pair={} price={} qty={} buyerIsMaker={} -> {} eligible {} order(s)",
                pair, price, qty, buyerIsMaker, eligible.size(), candidateSide);

        limitMatchHook.onMatch(pair, price, qty, buyerIsMaker, eligible);
    }
}
