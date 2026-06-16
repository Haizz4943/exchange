package com.haizz.exchange.order.application;

import com.haizz.exchange.common.event.order.OrderCancelledEvent;
import com.haizz.exchange.common.event.order.OrderFilledEvent;
import com.haizz.exchange.common.event.order.OrderPartiallyFilledEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Applies matching-engine lifecycle events ({@code matching.events.v1}) to the
 * local order aggregate (SR-042).
 *
 * <p><b>STATUS: STUB.</b> The Matching Engine is not built yet, so the exact event
 * shapes and the freeze-reconciliation contract are not finalised. The wiring
 * (consumer → use case) exists and boots cleanly, but no order is mutated here.
 * When the engine lands, each handler must (inside one transaction):
 * <ol>
 *   <li>load the order with a write lock,</li>
 *   <li>call {@link com.haizz.exchange.order.domain.Order#applyFill} /
 *       {@link com.haizz.exchange.order.domain.Order#markCancelled},</li>
 *   <li>persist the new state, and</li>
 *   <li>release any residual frozen balance at the Wallet Service.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessFillEventUseCase {

    public void onPartiallyFilled(OrderPartiallyFilledEvent event) {
        // TODO(matching): load order, applyFill(filledQuantity-delta, fillPrice),
        //  persist PARTIALLY_FILLED, release residual freeze if any.
        log.info("[STUB] OrderPartiallyFilled orderId={} filledQty={} fillPrice={} — not applied yet",
                event.orderId(), event.filledQuantity(), event.fillPrice());
    }

    public void onFilled(OrderFilledEvent event) {
        // TODO(matching): load order, applyFill completing it → FILLED,
        //  persist, release residual freeze (buy slippage buffer leftover).
        log.info("[STUB] OrderFilled orderId={} filledQty={} avgPrice={} — not applied yet",
                event.orderId(), event.filledQuantity(), event.avgPrice());
    }

    public void onCancelled(OrderCancelledEvent event) {
        // TODO(matching): load order, markCancelled() (terminal), persist,
        //  release remaining frozen amount for the unfilled portion.
        log.info("[STUB] OrderCancelled orderId={} reason={} — not applied yet",
                event.orderId(), event.reason());
    }
}
