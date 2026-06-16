package com.haizz.exchange.order.application;

import com.haizz.exchange.common.enums.OrderSide;
import com.haizz.exchange.common.event.order.OrderCancelledEvent;
import com.haizz.exchange.common.event.order.OrderFilledEvent;
import com.haizz.exchange.common.event.order.OrderPartiallyFilledEvent;
import com.haizz.exchange.order.application.FillPersister.FillResult;
import com.haizz.exchange.order.infrastructure.client.WalletClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * Applies matching-engine lifecycle events ({@code matching.events.v1}) to the
 * local order aggregate (SR-042) and releases any residual frozen balance on
 * terminal states.
 *
 * <h2>Residual-frozen model (Order-owned)</h2>
 * The Matching Engine sets {@code residualFrozenAmount=0} on trade events, so the
 * Wallet does NOT release the leftover freeze. The Order service owns residual
 * release because it knows the original {@code freezeAmount}. Per fill the Wallet
 * has already debited the consumed frozen portion; when an order reaches a
 * TERMINAL state the leftover must be released by Order:
 * <ul>
 *   <li><b>BUY:</b> consumedQuote = filledQuantity × avgFillPrice (VWAP);
 *       residual = freezeAmount − consumedQuote (freezeAsset = quote).</li>
 *   <li><b>SELL:</b> consumedBase = filledQuantity;
 *       residual = freezeAmount − filledQuantity (freezeAsset = base; 0 on full fill).</li>
 * </ul>
 * Residual is rounded DOWN to 8 dp and clamped to ≥ 0 (never over-release). It is
 * released via {@code walletClient.unfreeze(userId, freezeAsset, residual,
 * orderId, "FILL_RESIDUAL")} — idempotent by (referenceId, reason), so replay-safe.
 *
 * <p>The reason {@code "FILL_RESIDUAL"} is DISTINCT from the user-initiated
 * cancel's {@code "CANCELLED"} so the two release paths never collide / double-release.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessFillEventUseCase {

    /** Unfreeze ledger reason for the Order-owned residual release (distinct from user-cancel). */
    static final String RESIDUAL_REASON = "FILL_RESIDUAL";
    /** Scale for the released residual (rounded DOWN so we never over-release). */
    private static final int RELEASE_SCALE = 8;

    private final FillPersister fillPersister;
    private final WalletClient walletClient;

    /**
     * Partial fill: convert the event's CUMULATIVE filledQuantity to a delta and
     * apply it. No unfreeze — a partial fill is not terminal.
     */
    public void onPartiallyFilled(OrderPartiallyFilledEvent event) {
        UUID orderId = event.orderId();
        // Pass the event's CUMULATIVE filledQuantity; the persister converts it to a
        // delta under the write-lock and skips a non-positive (replay) delta.
        FillResult result = fillPersister.applyPartial(orderId,
                event.filledQuantity(), event.fillPrice());
        switch (result.outcome()) {
            case MISSING -> log.warn("OrderPartiallyFilled for unknown order orderId={} "
                    + "cumulativeFilled={} — order not visible locally yet, skipping (MVP)",
                    orderId, event.filledQuantity());
            case SKIPPED -> log.info("OrderPartiallyFilled ignored orderId={} — order already "
                    + "terminal or idempotent replay (delta<=0)", orderId);
            case APPLIED -> log.info("OrderPartiallyFilled applied orderId={} newFilled={}",
                    orderId, result.filledQuantity());
        }
    }

    /**
     * Full fill: complete the order to FILLED (applying the remaining delta) then,
     * after the persist tx commits, release the residual frozen balance.
     */
    public void onFilled(OrderFilledEvent event) {
        UUID orderId = event.orderId();
        FillResult result = fillPersister.complete(orderId, event.filledQuantity(), event.avgPrice());
        switch (result.outcome()) {
            case MISSING -> {
                log.warn("OrderFilled for unknown order orderId={} filled={} — order not visible "
                        + "locally yet, skipping (MVP)", orderId, event.filledQuantity());
                return;
            }
            case SKIPPED -> {
                log.info("OrderFilled ignored orderId={} — order already terminal (idempotent)",
                        orderId);
                return;
            }
            case APPLIED -> {
                log.info("OrderFilled applied orderId={} → FILLED filled={} avg={}",
                        orderId, result.filledQuantity(), result.avgFillPrice());
                releaseResidual(orderId, result);
            }
        }
    }

    /**
     * Matching-driven terminal cancellation (market-order auto-cancel / reject —
     * reason {@code MARKET_PARTIAL} or {@code REJECTED}). Transitions to CANCELLED,
     * then releases the residual for the unfilled portion (full freezeAmount when 0
     * fills). DIFFERENT from the user-initiated DELETE
     * ({@link CancelOrderUseCase}, reason {@code "CANCELLED"}); the distinct
     * {@code "FILL_RESIDUAL"} reason guards against double-release.
     */
    public void onCancelled(OrderCancelledEvent event) {
        UUID orderId = event.orderId();
        FillResult result = fillPersister.cancel(orderId);
        switch (result.outcome()) {
            case MISSING -> log.warn("OrderCancelled for unknown order orderId={} reason={} — "
                    + "order not visible locally yet, skipping (MVP)", orderId, event.reason());
            case SKIPPED -> log.info("OrderCancelled ignored orderId={} reason={} — order already "
                    + "terminal", orderId, event.reason());
            case APPLIED -> {
                log.info("OrderCancelled applied orderId={} reason={} → CANCELLED",
                        orderId, event.reason());
                releaseResidual(orderId, result);
            }
        }
    }

    /**
     * Computes the residual frozen amount per the BUY/SELL model and releases it
     * (only if {@code > 0}) AFTER the persist tx has committed. Mirrors
     * {@link CancelOrderUseCase}'s persist-then-unfreeze ordering. On unfreeze
     * failure we log for reconciliation (idempotent retry is safe).
     */
    private void releaseResidual(UUID orderId, FillResult result) {
        BigDecimal residual = computeResidual(result);
        if (residual.signum() <= 0) {
            log.debug("No residual to release for orderId={} (residual={})", orderId, residual);
            return;
        }
        try {
            walletClient.unfreeze(result.userId(), result.freezeAsset(), residual,
                    orderId.toString(), RESIDUAL_REASON);
            log.info("Released residual freeze orderId={} asset={} amount={} reason={}",
                    orderId, result.freezeAsset(), residual, RESIDUAL_REASON);
        } catch (RuntimeException e) {
            log.error("Order state persisted but residual unfreeze FAILED. orderId={} "
                            + "userId={} asset={} residual={} — frozen balance needs reconciliation",
                    orderId, result.userId(), result.freezeAsset(), residual, e);
        }
    }

    /**
     * Residual = freezeAmount − consumed, where consumed is:
     * <ul>
     *   <li>BUY: filledQuantity × avgFillPrice (VWAP)</li>
     *   <li>SELL: filledQuantity</li>
     * </ul>
     * Rounded DOWN to {@value #RELEASE_SCALE} dp; clamped to ≥ 0.
     */
    static BigDecimal computeResidual(FillResult result) {
        BigDecimal freeze = result.freezeAmount() != null ? result.freezeAmount() : BigDecimal.ZERO;
        BigDecimal filled = result.filledQuantity() != null
                ? result.filledQuantity() : BigDecimal.ZERO;

        BigDecimal consumed;
        if (result.side() == OrderSide.BUY) {
            BigDecimal avg = result.avgFillPrice() != null ? result.avgFillPrice() : BigDecimal.ZERO;
            consumed = filled.multiply(avg);
        } else {
            consumed = filled;
        }

        BigDecimal residual = freeze.subtract(consumed)
                .setScale(RELEASE_SCALE, RoundingMode.DOWN);
        return residual.signum() < 0 ? BigDecimal.ZERO : residual;
    }
}
