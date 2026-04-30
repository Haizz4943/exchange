package com.haizz.exchange.marketdata.infrastructure.provider.binance.mapper;

import com.haizz.exchange.marketdata.domain.PairSymbol;
import com.haizz.exchange.marketdata.domain.TradeObservation;
import com.haizz.exchange.marketdata.infrastructure.provider.binance.dto.BinanceTradeEvent;

import java.math.BigDecimal;
import java.time.Instant;

public final class BinanceTradeMapper {

    private BinanceTradeMapper() {}

    public static TradeObservation toObservation(BinanceTradeEvent event) {
        return new TradeObservation(
                PairSymbol.of(event.symbol()),
                new BigDecimal(event.price()),
                new BigDecimal(event.quantity()),
                event.buyerIsMaker(),
                event.tradeId(),
                Instant.ofEpochMilli(event.tradeTime())
        );
    }
}
