package com.haizz.exchange.matching.application;

import com.haizz.exchange.matching.domain.ResidentOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Phase-2 placeholder for {@link MarketOrderHook}: logs the order that WOULD execute
 * but performs no fills. Phase 3 supplies the real implementation.
 */
@Slf4j
@Component
public class NoOpMarketOrderHook implements MarketOrderHook {

    @Override
    public void handle(ResidentOrder marketOrder) {
        // TODO(phase3): execute MARKET order immediately against best book / external price.
        log.info("[stub] MARKET order received orderId={} pair={} side={} qty={} (no fill in phase 2)",
                marketOrder.getOrderId(), marketOrder.getPair(),
                marketOrder.getSide(), marketOrder.getTotalQuantity());
    }
}
