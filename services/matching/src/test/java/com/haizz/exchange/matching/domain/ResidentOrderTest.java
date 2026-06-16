package com.haizz.exchange.matching.domain;

import com.haizz.exchange.common.enums.OrderSide;
import com.haizz.exchange.common.enums.OrderType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure unit tests for the mutable {@link ResidentOrder} fill bookkeeping. */
class ResidentOrderTest {

    private static ResidentOrder order(String total, String filled) {
        return new ResidentOrder(UUID.randomUUID(), UUID.randomUUID(), "BTCUSDT", OrderSide.BUY,
                OrderType.LIMIT, new BigDecimal(total), new BigDecimal("60000"), Instant.now(),
                filled == null ? null : new BigDecimal(filled));
    }

    @Test
    void nullFilledQuantity_defaultsToZero() {
        ResidentOrder o = order("2", null);
        assertThat(o.getFilledQuantity()).isEqualByComparingTo("0");
        assertThat(o.remainingQuantity()).isEqualByComparingTo("2");
        assertThat(o.isFullyFilled()).isFalse();
    }

    @Test
    void addFill_accumulatesAndComputesRemaining() {
        ResidentOrder o = order("2", "0");
        o.addFill(new BigDecimal("0.5"));
        assertThat(o.getFilledQuantity()).isEqualByComparingTo("0.5");
        assertThat(o.remainingQuantity()).isEqualByComparingTo("1.5");
        assertThat(o.isFullyFilled()).isFalse();
    }

    @Test
    void isFullyFilled_trueWhenRemainingZeroOrNegative() {
        ResidentOrder o = order("2", "0");
        o.addFill(new BigDecimal("2"));
        assertThat(o.isFullyFilled()).isTrue();
        assertThat(o.remainingQuantity()).isEqualByComparingTo("0");
    }
}
