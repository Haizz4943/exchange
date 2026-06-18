package com.haizz.exchange.order.application;

import com.haizz.exchange.common.enums.OrderSide;
import com.haizz.exchange.order.domain.Order;
import com.haizz.exchange.order.domain.OrderState;
import com.haizz.exchange.order.infrastructure.persistence.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Transactional boundary for applying matching-engine fill/cancel events to the
 * local order aggregate (SR-042). Each method loads the order with a pessimistic
 * write-lock, mutates it, and persists in ONE transaction, returning a small
 * {@link FillResult} snapshot the use case needs to compute the post-commit
 * residual unfreeze.
 *
 * <p>Kept as a separate Spring bean (mirroring {@link OrderPersister} /
 * {@link CancelOrderPersister}) so the {@code @Transactional} proxy applies —
 * self-invocation from {@link ProcessFillEventUseCase} would bypass it.
 */
@Component
@RequiredArgsConstructor
public class FillPersister {

    private final OrderRepository orderRepository;

    /**
     * Applies a partial fill given the event's CUMULATIVE {@code targetFilled}.
     * The cumulative→delta conversion happens HERE, under the write-lock, where the
     * current filled quantity is authoritative: {@code delta = targetFilled −
     * currentFilled}. A non-positive delta is an idempotent replay and is SKIPPED
     * (no mutation). Partial fills never reach a terminal state, so no residual is
     * released here.
     *
     * @return {@link Outcome#APPLIED} with a snapshot, {@link Outcome#MISSING} if
     *         the order is not visible locally yet, or {@link Outcome#SKIPPED} if it
     *         is already terminal or the delta is non-positive (replay).
     */
    @Transactional
    public FillResult applyPartial(UUID orderId, BigDecimal targetFilled, BigDecimal fillPrice) {
        Optional<Order> maybe = orderRepository.findByIdForUpdate(orderId);
        if (maybe.isEmpty()) {
            return FillResult.missing();
        }
        Order order = maybe.get();
        if (order.getState().isTerminal()) {
            return FillResult.skipped(order);
        }
        BigDecimal current = order.getFilledQuantity() != null
                ? order.getFilledQuantity() : BigDecimal.ZERO;
        BigDecimal delta = targetFilled.subtract(current);
        if (delta.signum() <= 0) {
            // Idempotent replay / out-of-order event already superseded — skip.
            return FillResult.skipped(order);
        }
        order.applyFill(delta, fillPrice);
        Order saved = orderRepository.save(order);
        return FillResult.applied(saved);
    }

    /**
     * Completes an order to FILLED by applying the remaining {@code delta}
     * (cumulative target − current filled). If the order is already FILLED this is
     * an idempotent no-op ({@link Outcome#SKIPPED}); if missing, {@link Outcome#MISSING}.
     * On success the returned snapshot drives the residual unfreeze.
     */
    @Transactional
    public FillResult complete(UUID orderId, BigDecimal targetFilled, BigDecimal avgPrice) {
        Optional<Order> maybe = orderRepository.findByIdForUpdate(orderId);
        if (maybe.isEmpty()) {
            return FillResult.missing();
        }
        Order order = maybe.get();
        if (order.getState().isTerminal()) {
            // Already FILLED (or otherwise terminal) — idempotent replay.
            return FillResult.skipped(order);
        }
        BigDecimal current = order.getFilledQuantity() != null
                ? order.getFilledQuantity() : BigDecimal.ZERO;
        BigDecimal delta = targetFilled.subtract(current);
        if (delta.signum() > 0) {
            order.applyFill(delta, avgPrice);
        }
        Order saved = orderRepository.save(order);
        return FillResult.applied(saved);
    }

    /**
     * Applies a matching-driven terminal cancellation (market-order auto-cancel /
     * reject). If the order is already terminal this is a no-op
     * ({@link Outcome#SKIPPED}); if missing, {@link Outcome#MISSING}. On success the
     * snapshot drives the residual unfreeze for the unfilled portion.
     */
    @Transactional
    public FillResult cancel(UUID orderId) {
        Optional<Order> maybe = orderRepository.findByIdForUpdate(orderId);
        if (maybe.isEmpty()) {
            return FillResult.missing();
        }
        Order order = maybe.get();
        if (order.getState().isTerminal()) {
            return FillResult.skipped(order);
        }
        // A confirmation for an order already in CANCEL_REQUESTED is the terminal
        // ACK of a user-initiated DELETE (whose unfilled freeze release is already
        // owned by CancelOrderUseCase). Any other prior state is a matching-driven
        // cancel (REJECTED / MARKET_PARTIAL) whose residual the Order service must
        // still release. Capture that distinction for the use case.
        boolean wasCancelRequested = order.getState() == OrderState.CANCEL_REQUESTED;
        order.markCancelled();
        Order saved = orderRepository.save(order);
        return FillResult.cancelled(saved, wasCancelRequested);
    }

    /** Outcome of a persist attempt. */
    public enum Outcome {APPLIED, SKIPPED, MISSING}

    /**
     * Immutable snapshot of the order state needed AFTER commit to compute the
     * residual unfreeze, plus the {@link Outcome}. Captures primitives/values (not
     * the managed entity) so it is safe to read post-transaction.
     */
    public record FillResult(
            Outcome outcome,
            UUID userId,
            OrderSide side,
            BigDecimal freezeAmount,
            String freezeAsset,
            BigDecimal filledQuantity,
            BigDecimal avgFillPrice,
            boolean wasCancelRequested) {

        static FillResult missing() {
            return new FillResult(Outcome.MISSING, null, null, null, null, null, null, false);
        }

        static FillResult skipped(Order order) {
            return snapshot(Outcome.SKIPPED, order, false);
        }

        static FillResult applied(Order order) {
            return snapshot(Outcome.APPLIED, order, false);
        }

        /** Terminal cancellation; {@code wasCancelRequested} flags a user-cancel ACK. */
        static FillResult cancelled(Order order, boolean wasCancelRequested) {
            return snapshot(Outcome.APPLIED, order, wasCancelRequested);
        }

        private static FillResult snapshot(Outcome outcome, Order order, boolean wasCancelRequested) {
            return new FillResult(
                    outcome,
                    order.getUserId(),
                    order.getSide(),
                    order.getFreezeAmount(),
                    order.getFreezeAsset(),
                    order.getFilledQuantity(),
                    order.getAvgFillPrice(),
                    wasCancelRequested);
        }
    }
}
