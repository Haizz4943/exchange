package com.haizz.exchange.matching.application;

import com.haizz.exchange.common.enums.OrderType;
import com.haizz.exchange.matching.domain.OpenOrdersIndex;
import com.haizz.exchange.matching.domain.ResidentOrder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Applies order lifecycle events to the in-memory index.
 *
 * <p><b>Threading:</b> every method here mutates {@link OpenOrdersIndex} and so MUST be
 * invoked from the pair's dedicated executor thread (see {@code PairExecutorRegistry}).
 * The consumers are responsible for that dispatch — this class assumes single-threaded
 * access per pair.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderDispatcher {

    private final OpenOrdersIndex openOrdersIndex;
    private final MarketOrderHook marketOrderHook;
    private final FillEmitter fillEmitter;

    /**
     * Handle a newly placed order.
     * <ul>
     *   <li>MARKET → executes immediately via {@link MarketOrderHook} (NOT added to the index).</li>
     *   <li>LIMIT → rests in the {@link OpenOrdersIndex}.</li>
     * </ul>
     */
    public void onOrderPlaced(ResidentOrder ro) {
        if (ro.getType() == OrderType.MARKET) {
            log.debug("Dispatching MARKET order to hook orderId={} pair={}", ro.getOrderId(), ro.getPair());
            marketOrderHook.handle(ro);
            return;
        }
        openOrdersIndex.add(ro);
        log.info("Added LIMIT order to index orderId={} pair={} side={} price={} qty={}",
                ro.getOrderId(), ro.getPair(), ro.getSide(), ro.getLimitPrice(), ro.getTotalQuantity());
    }

    /**
     * Handle a user cancellation: remove the order from the index (if present) and
     * confirm back to the Order service so it can finalize CANCEL_REQUESTED →
     * CANCELLED. The confirmation is a state-only ACK — the Order service's DELETE
     * path already released the freeze.
     */
    public void onOrderCancelled(UUID orderId, UUID userId, String pair) {
        openOrdersIndex.remove(orderId);
        log.info("Removed cancelled order from index orderId={} pair={}", orderId, pair);
        fillEmitter.emitCancelConfirmed(orderId, userId, pair);
    }
}
