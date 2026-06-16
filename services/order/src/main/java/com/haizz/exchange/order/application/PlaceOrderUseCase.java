package com.haizz.exchange.order.application;

import com.haizz.exchange.common.enums.OrderSide;
import com.haizz.exchange.common.enums.OrderType;
import com.haizz.exchange.order.api.dto.OrderResponse;
import com.haizz.exchange.order.api.dto.PlaceOrderRequest;
import com.haizz.exchange.order.config.AppProperties;
import com.haizz.exchange.order.domain.FreezeCalculator;
import com.haizz.exchange.order.domain.Order;
import com.haizz.exchange.order.domain.OrderState;
import com.haizz.exchange.order.domain.OrderValidator;
import com.haizz.exchange.order.domain.TradingPair;
import com.haizz.exchange.order.domain.exception.DuplicateClientOrderIdException;
import com.haizz.exchange.order.domain.exception.InvalidOrderException;
import com.haizz.exchange.order.domain.exception.MarketDataUnavailableException;
import com.haizz.exchange.order.domain.exception.MaxOpenOrdersExceededException;
import com.haizz.exchange.order.domain.exception.PairNotSupportedException;
import com.haizz.exchange.order.infrastructure.client.MarketDataClient;
import com.haizz.exchange.order.infrastructure.client.WalletClient;
import com.haizz.exchange.order.infrastructure.persistence.OrderRepository;
import com.haizz.exchange.order.infrastructure.persistence.TradingPairRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Place-order use case (SR-030 → SR-037). Validates, computes the freeze amount,
 * freezes balance at the Wallet Service, then persists the order + outbox event
 * in one transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlaceOrderUseCase {

    /** Idempotency lookup window for a provided client_order_id. */
    private static final long IDEMPOTENCY_WINDOW_HOURS = 24;

    private static final List<OrderState> OPEN_STATES =
            List.of(OrderState.NEW, OrderState.OPEN, OrderState.PARTIALLY_FILLED);

    private final TradingPairRepository tradingPairRepository;
    private final OrderRepository orderRepository;
    private final WalletClient walletClient;
    private final MarketDataClient marketDataClient;
    private final OrderPersister orderPersister;
    private final AppProperties appProperties;

    public OrderResponse execute(UUID userId, PlaceOrderRequest req) {
        // 1. Parse enums + decimals (HTTP-level presence already validated).
        OrderSide side = parseSide(req.side());
        OrderType type = parseType(req.type());
        BigDecimal quantity = parseDecimal(req.quantity(), "INVALID_QUANTITY", "quantity");
        BigDecimal limitPrice = req.limitPrice() != null && !req.limitPrice().isBlank()
                ? parseDecimal(req.limitPrice(), "INVALID_PRICE", "limit_price")
                : null;
        String timeInForce = (req.timeInForce() == null || req.timeInForce().isBlank())
                ? "GTC" : req.timeInForce().trim().toUpperCase();

        // 2. Type rules.
        OrderValidator.validatePriceRules(type, limitPrice);

        // 3. Pair must exist and be enabled.
        TradingPair pair = tradingPairRepository.findBySymbolAndEnabledTrue(req.pair())
                .orElseThrow(() -> new PairNotSupportedException(req.pair()));

        // 4. Business validation (SR-033).
        OrderValidator.validateQuantity(quantity, pair.getStepSize());
        OrderValidator.validateLimitPrice(type, limitPrice, pair.getTickSize());

        // Reference price: LIMIT uses limit_price; MARKET uses best_ask.
        BigDecimal bestAsk = null;
        BigDecimal notionalPrice;
        if (type == OrderType.LIMIT) {
            notionalPrice = limitPrice;
        } else {
            bestAsk = fetchBestAsk(req.pair());
            notionalPrice = bestAsk;
        }
        OrderValidator.validateMinNotional(notionalPrice, quantity, pair.getMinNotional());

        // 5. Max open orders per pair (cap).
        long openCount = orderRepository.countByUserIdAndPairAndStateIn(
                userId, pair.getSymbol(), OPEN_STATES);
        if (openCount >= appProperties.limits().maxOpenOrdersPerPair()) {
            throw new MaxOpenOrdersExceededException(
                    pair.getSymbol(), appProperties.limits().maxOpenOrdersPerPair());
        }

        // 6. Idempotency (SR-037): duplicate client_order_id within 24h → 409.
        if (req.clientOrderId() != null) {
            Optional<Order> existing = orderRepository
                    .findByUserIdAndClientOrderId(userId, req.clientOrderId());
            if (existing.isPresent()) {
                Instant cutoff = Instant.now().minus(IDEMPOTENCY_WINDOW_HOURS, ChronoUnit.HOURS);
                if (existing.get().getCreatedAt() == null
                        || existing.get().getCreatedAt().isAfter(cutoff)) {
                    throw new DuplicateClientOrderIdException(req.clientOrderId().toString());
                }
            }
        }

        // 7. Compute freeze amount (SR-034/035).
        BigDecimal takerRate = appProperties.fees().takerRate();
        FreezeCalculator.Freeze freeze = FreezeCalculator.compute(
                side, type, quantity, limitPrice, bestAsk, takerRate,
                pair.getBaseAsset(), pair.getQuoteAsset());
        BigDecimal freezeAmount = freeze.amount();
        String freezeAsset = freeze.asset();

        // 8. Build the order (so we have an id) then freeze via Wallet.
        Order order = Order.newOrder(userId, req.clientOrderId(), pair.getSymbol(), side, type,
                quantity, limitPrice, timeInForce, freezeAmount, freezeAsset);

        // Freeze is a side-effecting remote call done BEFORE the DB transaction.
        // It is idempotent by referenceId (orderId), so a retry is safe.
        walletClient.freeze(userId, freezeAsset, freezeAmount, order.getId().toString());

        // 9. Persist order + outbox event atomically (SR-036/040).
        try {
            Order saved = orderPersister.persist(order);
            return OrderResponse.from(saved);
        } catch (RuntimeException e) {
            // Freeze already succeeded but persist failed. Freeze is idempotent
            // by orderId; a later reconciliation phase can release orphans.
            log.error("Order persist failed AFTER successful freeze. userId={} orderId={} "
                            + "freezeAsset={} freezeAmount={} — frozen balance may need reconciliation",
                    userId, order.getId(), freezeAsset, freezeAmount, e);
            throw e;
        }
    }

    private OrderSide parseSide(String raw) {
        try {
            return OrderSide.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new InvalidOrderException("INVALID_SIDE", "Invalid side: " + raw);
        }
    }

    private OrderType parseType(String raw) {
        try {
            return OrderType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new InvalidOrderException("INVALID_ORDER_TYPE", "Invalid order type: " + raw);
        }
    }

    private BigDecimal parseDecimal(String raw, String code, String field) {
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException | NullPointerException e) {
            throw new InvalidOrderException(code, "Invalid decimal for " + field + ": " + raw);
        }
    }

    private BigDecimal fetchBestAsk(String pair) {
        MarketDataClient.Ticker ticker = marketDataClient.getTicker(pair);
        BigDecimal bestAsk = ticker.bestAsk();
        if (bestAsk == null || bestAsk.signum() <= 0) {
            throw new MarketDataUnavailableException(
                    "best_ask unavailable for pair=" + pair);
        }
        return bestAsk;
    }
}
