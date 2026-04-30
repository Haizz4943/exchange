package com.haizz.exchange.marketdata.infrastructure.provider.binance.mapper;

import com.haizz.exchange.marketdata.domain.DepthSnapshot;
import com.haizz.exchange.marketdata.domain.PairSymbol;
import com.haizz.exchange.marketdata.infrastructure.provider.binance.dto.BinanceDepthEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

public final class BinanceDepthMapper {

    private BinanceDepthMapper() {}

    public static DepthSnapshot toSnapshot(BinanceDepthEvent event) {
        List<List<BigDecimal>> bids = toLevels(event.bids(), true);
        List<List<BigDecimal>> asks = toLevels(event.asks(), false);
        return new DepthSnapshot(PairSymbol.of(event.symbol()), bids, asks, Instant.now());
    }

    private static List<List<BigDecimal>> toLevels(List<List<String>> rawLevels, boolean bids) {
        var comparator = bids
                ? Comparator.<BigDecimal>reverseOrder()
                : Comparator.<BigDecimal>naturalOrder();
        return rawLevels.stream()
                .map(level -> List.of(new BigDecimal(level.get(0)), new BigDecimal(level.get(1))))
                .sorted(Comparator.comparing(level -> level.get(0), comparator))
                .toList();
    }
}
