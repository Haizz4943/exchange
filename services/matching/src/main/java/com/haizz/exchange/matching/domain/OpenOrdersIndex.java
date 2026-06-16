package com.haizz.exchange.matching.domain;

import com.haizz.exchange.common.enums.OrderSide;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * In-memory index of open/partially-filled limit orders, keyed by pair.
 *
 * <p>Thread-safety is intentionally NOT handled here: a later phase routes
 * all mutations for a given pair through a single-threaded per-pair executor,
 * so plain (non-concurrent) collections are sufficient.
 *
 * <p>This phase implements {@code add}/{@code remove}/{@code get}; the real
 * price-time matching candidate selection lands in the matching-core phase —
 * {@link #eligibleLimitOrders} is a compiling stub for now.
 */
@Component
public class OpenOrdersIndex {

    private final Map<String, PerPairIndex> byPair = new HashMap<>();
    /** Global orderId -> pair lookup, so remove/get need only the orderId. */
    private final Map<UUID, String> pairByOrderId = new HashMap<>();

    public void add(ResidentOrder order) {
        PerPairIndex idx = byPair.computeIfAbsent(order.getPair(), p -> new PerPairIndex());
        idx.add(order);
        pairByOrderId.put(order.getOrderId(), order.getPair());
    }

    public void remove(UUID orderId) {
        String pair = pairByOrderId.remove(orderId);
        if (pair == null) {
            return;
        }
        PerPairIndex idx = byPair.get(pair);
        if (idx != null) {
            idx.remove(orderId);
        }
    }

    public ResidentOrder get(UUID orderId) {
        String pair = pairByOrderId.get(orderId);
        if (pair == null) {
            return null;
        }
        PerPairIndex idx = byPair.get(pair);
        return idx == null ? null : idx.get(orderId);
    }

    /**
     * Returns resident limit orders on {@code side} of {@code pair} that are
     * eligible to match against an external price, in FIFO (price-time) order.
     *
     * <p>STUB: real selection logic lands in the matching-core phase.
     */
    public List<ResidentOrder> eligibleLimitOrders(String pair, OrderSide side,
                                                   BigDecimal externalPrice) {
        return Collections.emptyList();
    }

    /**
     * Per-pair order book slice. Bids descending by price, asks ascending,
     * each price level a FIFO deque (price-time priority).
     */
    static final class PerPairIndex {

        private final TreeMap<BigDecimal, Deque<ResidentOrder>> bids =
                new TreeMap<>(Comparator.reverseOrder());
        private final TreeMap<BigDecimal, Deque<ResidentOrder>> asks =
                new TreeMap<>();
        private final Map<UUID, ResidentOrder> byId = new HashMap<>();

        void add(ResidentOrder order) {
            byId.put(order.getOrderId(), order);
            BigDecimal price = order.getLimitPrice();
            if (price == null) {
                // MARKET orders do not rest in the book; tracked by id only.
                return;
            }
            book(order.getSide()).computeIfAbsent(price, p -> new ArrayDeque<>()).addLast(order);
        }

        void remove(UUID orderId) {
            ResidentOrder order = byId.remove(orderId);
            if (order == null || order.getLimitPrice() == null) {
                return;
            }
            TreeMap<BigDecimal, Deque<ResidentOrder>> book = book(order.getSide());
            Deque<ResidentOrder> level = book.get(order.getLimitPrice());
            if (level != null) {
                level.removeIf(o -> o.getOrderId().equals(orderId));
                if (level.isEmpty()) {
                    book.remove(order.getLimitPrice());
                }
            }
        }

        ResidentOrder get(UUID orderId) {
            return byId.get(orderId);
        }

        private TreeMap<BigDecimal, Deque<ResidentOrder>> book(OrderSide side) {
            return side == OrderSide.BUY ? bids : asks;
        }
    }
}
