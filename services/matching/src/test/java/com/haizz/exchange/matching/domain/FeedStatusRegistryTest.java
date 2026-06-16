package com.haizz.exchange.matching.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure unit tests for {@link FeedStatusRegistry} tradeability rules. */
class FeedStatusRegistryTest {

    @Test
    void unknownPair_defaultsHealthyAndTradeable() {
        FeedStatusRegistry registry = new FeedStatusRegistry();
        assertThat(registry.statusOf("BTCUSDT")).isEqualTo(FeedStatusRegistry.FeedStatus.HEALTHY);
        assertThat(registry.isTradeable("BTCUSDT")).isTrue();
    }

    @Test
    void degraded_isNotTradeable() {
        FeedStatusRegistry registry = new FeedStatusRegistry();
        registry.markDegraded("BTCUSDT");
        assertThat(registry.statusOf("BTCUSDT")).isEqualTo(FeedStatusRegistry.FeedStatus.DEGRADED);
        assertThat(registry.isTradeable("BTCUSDT")).isFalse();
    }

    @Test
    void disconnected_isNotTradeable() {
        FeedStatusRegistry registry = new FeedStatusRegistry();
        registry.update("BTCUSDT", FeedStatusRegistry.FeedStatus.DISCONNECTED);
        assertThat(registry.isTradeable("BTCUSDT")).isFalse();
    }

    @Test
    void stale_isStillTradeable() {
        FeedStatusRegistry registry = new FeedStatusRegistry();
        registry.update("BTCUSDT", FeedStatusRegistry.FeedStatus.STALE);
        assertThat(registry.isTradeable("BTCUSDT")).isTrue();
    }

    @Test
    void recovered_afterDegraded_isTradeableAgain() {
        FeedStatusRegistry registry = new FeedStatusRegistry();
        registry.markDegraded("BTCUSDT");
        registry.markRecovered("BTCUSDT");
        assertThat(registry.statusOf("BTCUSDT")).isEqualTo(FeedStatusRegistry.FeedStatus.HEALTHY);
        assertThat(registry.isTradeable("BTCUSDT")).isTrue();
    }
}
