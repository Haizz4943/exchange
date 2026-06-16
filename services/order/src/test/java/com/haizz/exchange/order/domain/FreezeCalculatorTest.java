package com.haizz.exchange.order.domain;

import com.haizz.exchange.common.enums.OrderSide;
import com.haizz.exchange.common.enums.OrderType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure unit tests for the freeze-amount formula (SR-034/035).
 */
class FreezeCalculatorTest {

    private static final BigDecimal TAKER = new BigDecimal("0.001");

    @Test
    @DisplayName("BUY LIMIT: 0.1 BTC @55000, taker 0.001 → 5505.5 USDT")
    void buyLimit() {
        FreezeCalculator.Freeze f = FreezeCalculator.compute(
                OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("0.1"), new BigDecimal("55000"),
                null, TAKER, "BTC", "USDT");

        assertEquals(0, new BigDecimal("5505.5").compareTo(f.amount()),
                "expected 0.1*55000*1.001 = 5505.5, got " + f.amount());
        assertEquals("USDT", f.asset());
    }

    @Test
    @DisplayName("BUY MARKET: applies slippage AND taker buffers, rounds UP at scale 8")
    void buyMarket() {
        // 0.1 * 55000 * 1.0005 * 1.001 = 5508.25275
        FreezeCalculator.Freeze f = FreezeCalculator.compute(
                OrderSide.BUY, OrderType.MARKET,
                new BigDecimal("0.1"), null,
                new BigDecimal("55000"), TAKER, "BTC", "USDT");

        assertEquals(0, new BigDecimal("5508.25275").compareTo(f.amount()),
                "expected 0.1*55000*1.0005*1.001, got " + f.amount());
        assertEquals("USDT", f.asset());
    }

    @Test
    @DisplayName("BUY: quote freeze is rounded UP (never under-freeze) at scale 8")
    void buyRoundsUp() {
        // price chosen so the raw product has >8 dp and must round up.
        FreezeCalculator.Freeze f = FreezeCalculator.compute(
                OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("0.123456789"), new BigDecimal("1"),
                null, BigDecimal.ZERO, "BTC", "USDT");

        // 0.123456789 * 1 = 0.123456789 → UP at scale 8 → 0.12345679
        assertEquals(new BigDecimal("0.12345679"), f.amount());
    }

    @Test
    @DisplayName("SELL: freeze equals base quantity (no fee/slippage), asset = base")
    void sell() {
        FreezeCalculator.Freeze f = FreezeCalculator.compute(
                OrderSide.SELL, OrderType.LIMIT,
                new BigDecimal("0.05"), new BigDecimal("55000"),
                null, TAKER, "BTC", "USDT");

        assertEquals(0, new BigDecimal("0.05").compareTo(f.amount()));
        assertEquals("BTC", f.asset());
    }

    @Test
    @DisplayName("SELL MARKET: still freezes raw base quantity")
    void sellMarket() {
        FreezeCalculator.Freeze f = FreezeCalculator.compute(
                OrderSide.SELL, OrderType.MARKET,
                new BigDecimal("0.05"), null,
                new BigDecimal("55000"), TAKER, "BTC", "USDT");

        assertEquals(0, new BigDecimal("0.05").compareTo(f.amount()));
        assertEquals("BTC", f.asset());
    }
}
