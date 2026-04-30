package com.haizz.exchange.marketdata.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record DepthSnapshot(
        PairSymbol pair,
        List<List<BigDecimal>> bids,
        List<List<BigDecimal>> asks,
        Instant updatedAt
) {}
