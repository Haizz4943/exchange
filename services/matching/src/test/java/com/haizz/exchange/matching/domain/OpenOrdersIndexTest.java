package com.haizz.exchange.matching.domain;

import com.haizz.exchange.common.enums.OrderSide;
import com.haizz.exchange.common.enums.OrderType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link OpenOrdersIndex} — add/remove/get + eligibility FIFO and edges.
 * No Spring, no DB.
 */
class OpenOrdersIndexTest {

    private static ResidentOrder limit(OrderSide side, String price, String qty, Instant createdAt) {
        return new ResidentOrder(UUID.randomUUID(), UUID.randomUUID(), "BTCUSDT", side,
                OrderType.LIMIT, new BigDecimal(qty), new BigDecimal(price), createdAt, BigDecimal.ZERO);
    }

    private static ResidentOrder market(OrderSide side, String qty) {
        return new ResidentOrder(UUID.randomUUID(), UUID.randomUUID(), "BTCUSDT", side,
                OrderType.MARKET, new BigDecimal(qty), null, Instant.now(), BigDecimal.ZERO);
    }

    @Test
    void add_get_remove_roundtrip() {
        OpenOrdersIndex index = new OpenOrdersIndex();
        ResidentOrder o = limit(OrderSide.BUY, "60000", "1.0", Instant.now());

        index.add(o);
        assertThat(index.get(o.getOrderId())).isSameAs(o);

        index.remove(o.getOrderId());
        assertThat(index.get(o.getOrderId())).isNull();
    }

    @Test
    void get_unknownOrder_returnsNull() {
        OpenOrdersIndex index = new OpenOrdersIndex();
        assertThat(index.get(UUID.randomUUID())).isNull();
    }

    @Test
    void remove_unknownOrder_isNoOp() {
        OpenOrdersIndex index = new OpenOrdersIndex();
        // Should not throw.
        index.remove(UUID.randomUUID());
        assertThat(index.get(UUID.randomUUID())).isNull();
    }

    @Test
    void eligible_emptyBook_returnsEmptyList() {
        OpenOrdersIndex index = new OpenOrdersIndex();
        assertThat(index.eligibleLimitOrders("BTCUSDT", OrderSide.BUY, new BigDecimal("60000")))
                .isEmpty();
    }

    @Test
    void eligible_unknownPair_returnsEmptyList() {
        OpenOrdersIndex index = new OpenOrdersIndex();
        index.add(limit(OrderSide.BUY, "60000", "1", Instant.now()));
        assertThat(index.eligibleLimitOrders("ETHUSDT", OrderSide.BUY, new BigDecimal("3000")))
                .isEmpty();
    }

    @Test
    void eligible_buy_includesAtOrAboveExternalPrice_onlyNoneBelow() {
        OpenOrdersIndex index = new OpenOrdersIndex();
        ResidentOrder above = limit(OrderSide.BUY, "60001", "1", Instant.now());
        ResidentOrder at = limit(OrderSide.BUY, "60000", "1", Instant.now());
        ResidentOrder below = limit(OrderSide.BUY, "59999", "1", Instant.now());
        index.add(above);
        index.add(at);
        index.add(below);

        List<ResidentOrder> eligible =
                index.eligibleLimitOrders("BTCUSDT", OrderSide.BUY, new BigDecimal("60000"));

        assertThat(eligible).containsExactlyInAnyOrder(above, at);
        assertThat(eligible).doesNotContain(below);
    }

    @Test
    void eligible_sell_includesAtOrBelowExternalPrice_onlyNoneAbove() {
        OpenOrdersIndex index = new OpenOrdersIndex();
        ResidentOrder below = limit(OrderSide.SELL, "59999", "1", Instant.now());
        ResidentOrder at = limit(OrderSide.SELL, "60000", "1", Instant.now());
        ResidentOrder above = limit(OrderSide.SELL, "60001", "1", Instant.now());
        index.add(below);
        index.add(at);
        index.add(above);

        List<ResidentOrder> eligible =
                index.eligibleLimitOrders("BTCUSDT", OrderSide.SELL, new BigDecimal("60000"));

        assertThat(eligible).containsExactlyInAnyOrder(below, at);
        assertThat(eligible).doesNotContain(above);
    }

    @Test
    void eligible_returnsFifoAcrossPriceLevels_oldestFirst() {
        OpenOrdersIndex index = new OpenOrdersIndex();
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        // Add out of time order across different price levels, all eligible.
        ResidentOrder third = limit(OrderSide.BUY, "60002", "1", t0.plusSeconds(30));
        ResidentOrder first = limit(OrderSide.BUY, "60000", "1", t0.plusSeconds(10));
        ResidentOrder second = limit(OrderSide.BUY, "60001", "1", t0.plusSeconds(20));
        index.add(third);
        index.add(first);
        index.add(second);

        List<ResidentOrder> eligible =
                index.eligibleLimitOrders("BTCUSDT", OrderSide.BUY, new BigDecimal("60000"));

        assertThat(eligible).containsExactly(first, second, third);
    }

    @Test
    void eligible_samePriceLevel_preservesInsertionFifo() {
        OpenOrdersIndex index = new OpenOrdersIndex();
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        ResidentOrder a = limit(OrderSide.SELL, "60000", "1", t0.plusSeconds(1));
        ResidentOrder b = limit(OrderSide.SELL, "60000", "1", t0.plusSeconds(2));
        ResidentOrder c = limit(OrderSide.SELL, "60000", "1", t0.plusSeconds(3));
        index.add(a);
        index.add(b);
        index.add(c);

        List<ResidentOrder> eligible =
                index.eligibleLimitOrders("BTCUSDT", OrderSide.SELL, new BigDecimal("60000"));

        assertThat(eligible).containsExactly(a, b, c);
    }

    @Test
    void eligible_noneMatch_returnsEmpty() {
        OpenOrdersIndex index = new OpenOrdersIndex();
        index.add(limit(OrderSide.BUY, "59000", "1", Instant.now()));
        assertThat(index.eligibleLimitOrders("BTCUSDT", OrderSide.BUY, new BigDecimal("60000")))
                .isEmpty();
    }

    @Test
    void removedOrder_noLongerEligible() {
        OpenOrdersIndex index = new OpenOrdersIndex();
        ResidentOrder o = limit(OrderSide.BUY, "60000", "1", Instant.now());
        index.add(o);
        index.remove(o.getOrderId());

        assertThat(index.eligibleLimitOrders("BTCUSDT", OrderSide.BUY, new BigDecimal("60000")))
                .isEmpty();
    }

    @Test
    void marketOrder_trackedById_butNeverEligible() {
        OpenOrdersIndex index = new OpenOrdersIndex();
        ResidentOrder mo = market(OrderSide.BUY, "1");
        index.add(mo);

        assertThat(index.get(mo.getOrderId())).isSameAs(mo);
        assertThat(index.eligibleLimitOrders("BTCUSDT", OrderSide.BUY, new BigDecimal("1")))
                .isEmpty();
    }
}
