package com.haizz.exchange.matching.application;

import com.haizz.exchange.matching.domain.ResidentOrder;

/**
 * Seam for executing an incoming MARKET order against the book / external price.
 *
 * <p>Implemented by {@code MarketOrderMatcher}: market orders never rest in the index —
 * they fill immediately on arrival by walking the external depth.
 */
public interface MarketOrderHook {

    void handle(ResidentOrder marketOrder);
}
