package com.haizz.exchange.marketdata.infrastructure.provider.binance.mapper;

import com.haizz.exchange.marketdata.domain.PairMetadata;
import com.haizz.exchange.marketdata.domain.PairSymbol;
import com.haizz.exchange.marketdata.infrastructure.provider.binance.dto.BinanceExchangeInfo;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class BinanceExchangeInfoMapper {

    private BinanceExchangeInfoMapper() {}

    public static Map<PairSymbol, PairMetadata> toMetadataMap(
            BinanceExchangeInfo info, Set<PairSymbol> supportedPairs) {
        return info.symbols().stream()
                .filter(s -> supportedPairs.contains(PairSymbol.of(s.symbol())))
                .collect(Collectors.toMap(
                        s -> PairSymbol.of(s.symbol()),
                        s -> new PairMetadata(
                                PairSymbol.of(s.symbol()),
                                s.baseAsset(),
                                s.quoteAsset(),
                                new BigDecimal(s.tickSize()),
                                new BigDecimal(s.stepSize()),
                                new BigDecimal(s.minNotional()),
                                s.status(),
                                Instant.now()
                        )
                ));
    }
}
