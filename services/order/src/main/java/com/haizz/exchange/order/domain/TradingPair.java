package com.haizz.exchange.order.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Read-only reference data for a tradable pair (e.g. BTCUSDT).
 * Loaded from the {@code trading_pairs} table seeded by Flyway.
 */
@Entity
@Table(name = "trading_pairs")
@Getter
@NoArgsConstructor
public class TradingPair {

    @Id
    @Column(name = "symbol", length = 20)
    private String symbol;

    @Column(name = "base_asset", nullable = false, length = 10)
    private String baseAsset;

    @Column(name = "quote_asset", nullable = false, length = 10)
    private String quoteAsset;

    @Column(name = "tick_size", nullable = false, precision = 36, scale = 18)
    private BigDecimal tickSize;

    @Column(name = "step_size", nullable = false, precision = 36, scale = 18)
    private BigDecimal stepSize;

    @Column(name = "min_notional", nullable = false, precision = 36, scale = 18)
    private BigDecimal minNotional;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
