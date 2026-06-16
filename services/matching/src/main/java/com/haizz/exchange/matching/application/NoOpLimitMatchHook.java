package com.haizz.exchange.matching.application;

import com.haizz.exchange.matching.domain.ResidentOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Phase-2 placeholder for {@link LimitMatchHook}: logs how many resting limit orders
 * WOULD match an external trade but performs no fills. Phase 3 supplies the real
 * fill / trade-emission logic.
 */
@Slf4j
@Component
public class NoOpLimitMatchHook implements LimitMatchHook {

    @Override
    public void onMatch(String pair, BigDecimal externalPrice, BigDecimal externalQty,
                        boolean buyerIsMaker, List<ResidentOrder> eligible) {
        // TODO(phase3): fill eligible orders FIFO up to externalQty, emit trades + fees.
        log.info("[stub] External trade pair={} price={} qty={} buyerIsMaker={} -> {} eligible order(s) would match (no fill in phase 2)",
                pair, externalPrice, externalQty, buyerIsMaker, eligible.size());
    }
}
