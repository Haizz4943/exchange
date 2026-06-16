package com.haizz.exchange.matching.application;

import com.haizz.exchange.matching.domain.ResidentOrder;

/**
 * Seam for executing an incoming MARKET order against the book / external price.
 *
 * <p>Phase 2 wires a no-op logging implementation ({@link NoOpMarketOrderHook}).
 * Phase 3 replaces it with the real immediate-execution logic (market orders never
 * rest in the index — they fill on arrival).
 */
public interface MarketOrderHook {

    void handle(ResidentOrder marketOrder);
}
