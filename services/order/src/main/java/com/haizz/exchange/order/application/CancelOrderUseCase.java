package com.haizz.exchange.order.application;

import com.haizz.exchange.common.web.ForbiddenException;
import com.haizz.exchange.order.api.dto.OrderResponse;
import com.haizz.exchange.order.domain.Order;
import com.haizz.exchange.order.infrastructure.client.WalletClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * Cancel-order use case (SR-038/039/040). Validates ownership + cancellability,
 * computes the frozen amount to release for the still-unfilled portion,
 * transitions the order to CANCEL_REQUESTED, persists the new state + an
 * {@code OrderCancelled} outbox event in one transaction, then releases the
 * frozen balance at the Wallet Service AFTER commit (so funds are never released
 * for a cancel we failed to record).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CancelOrderUseCase {

    /** Cancellation reason recorded on the event / unfreeze ledger entry. */
    static final String CANCELLED_REASON = "CANCELLED";
    /** Scale for the released quote/base amount (rounded DOWN, see DECISIONS). */
    private static final int RELEASE_SCALE = 8;

    private final WalletClient walletClient;
    private final CancelOrderPersister cancelOrderPersister;

    public OrderResponse execute(UUID userId, UUID orderId) {
        // Persist CANCEL_REQUESTED + outbox atomically; returns the data we need
        // for the post-commit unfreeze plus the response.
        CancelResult result = cancelOrderPersister.cancel(userId, orderId);

        // Release the frozen balance for the unfilled portion AFTER the DB commit.
        // unfreeze is idempotent by (referenceId, reason); if this fails the order
        // is already CANCEL_REQUESTED and a reconciliation retry is safe.
        try {
            walletClient.unfreeze(userId, result.freezeAsset(), result.releaseAmount(),
                    orderId.toString(), CANCELLED_REASON);
        } catch (RuntimeException e) {
            log.error("Order cancel persisted but unfreeze FAILED. userId={} orderId={} "
                            + "freezeAsset={} releaseAmount={} — frozen balance needs reconciliation",
                    userId, orderId, result.freezeAsset(), result.releaseAmount(), e);
        }

        return result.response();
    }

    /**
     * Computes the frozen amount to release for the unfilled portion of an order.
     * {@code releaseAmount = freezeAmount × (quantity - filledQuantity) / quantity},
     * rounded DOWN at scale {@value #RELEASE_SCALE} so we never over-release.
     * With filledQuantity == 0 (no matching yet) this equals the full freezeAmount.
     */
    static BigDecimal computeReleaseAmount(Order order) {
        BigDecimal quantity = order.getQuantity();
        BigDecimal filled = order.getFilledQuantity() != null
                ? order.getFilledQuantity() : BigDecimal.ZERO;
        BigDecimal freeze = order.getFreezeAmount();

        if (quantity == null || quantity.signum() <= 0 || freeze == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal unfilled = quantity.subtract(filled);
        if (unfilled.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        if (filled.signum() == 0) {
            return freeze;
        }
        return freeze.multiply(unfilled)
                .divide(quantity, RELEASE_SCALE, RoundingMode.DOWN);
    }

    /** Carries the data needed for the post-commit unfreeze + the HTTP response. */
    record CancelResult(String freezeAsset, BigDecimal releaseAmount, OrderResponse response) {
    }
}
