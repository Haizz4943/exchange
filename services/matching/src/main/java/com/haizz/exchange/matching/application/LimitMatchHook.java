package com.haizz.exchange.matching.application;

import com.haizz.exchange.matching.domain.ResidentOrder;

import java.math.BigDecimal;
import java.util.List;

/**
 * Seam for matching resting LIMIT orders against an observed external trade.
 *
 * <p>Implemented by {@code LimitOrderMatcher}: distributes the external trade volume across
 * the eligible resting orders FIFO, persisting trades and emitting fill/fee events.
 */
public interface LimitMatchHook {

    /**
     * @param pair          the trading pair
     * @param externalPrice the external trade price that triggered eligibility
     * @param externalQty   the external trade quantity
     * @param buyerIsMaker  whether the external buyer was the maker side
     * @param eligible      resting limit orders eligible to match, in FIFO order
     */
    void onMatch(String pair, BigDecimal externalPrice, BigDecimal externalQty,
                 boolean buyerIsMaker, List<ResidentOrder> eligible);
}
