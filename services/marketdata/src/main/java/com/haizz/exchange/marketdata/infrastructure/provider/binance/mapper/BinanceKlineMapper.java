package com.haizz.exchange.marketdata.infrastructure.provider.binance.mapper;

import com.haizz.exchange.marketdata.domain.Candlestick;
import com.haizz.exchange.marketdata.domain.Interval;
import com.haizz.exchange.marketdata.domain.KlineUpdate;
import com.haizz.exchange.marketdata.domain.PairSymbol;
import com.haizz.exchange.marketdata.infrastructure.provider.binance.dto.BinanceKlineEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class BinanceKlineMapper {

    private BinanceKlineMapper() {}

    public static KlineUpdate toKlineUpdate(BinanceKlineEvent event) {
        var k = event.kline();
        return new KlineUpdate(
                PairSymbol.of(event.symbol()),
                Interval.fromValue(k.interval()),
                Instant.ofEpochMilli(k.openTime()),
                new BigDecimal(k.open()),
                new BigDecimal(k.high()),
                new BigDecimal(k.low()),
                new BigDecimal(k.close()),
                new BigDecimal(k.volume()),
                new BigDecimal(k.quoteVolume()),
                k.tradeCount(),
                Instant.ofEpochMilli(k.closeTime()),
                k.closed()
        );
    }

    /**
     * Binance REST /api/v3/klines returns arrays:
     * [openTime, open, high, low, close, volume, closeTime, quoteVolume, tradeCount, ...]
     */
    public static Candlestick fromKlineArray(String symbol, String interval, List<Object> klineArray) {
        return new Candlestick(
                PairSymbol.of(symbol),
                Interval.fromValue(interval),
                Instant.ofEpochMilli(((Number) klineArray.get(0)).longValue()),
                new BigDecimal(klineArray.get(1).toString()),
                new BigDecimal(klineArray.get(2).toString()),
                new BigDecimal(klineArray.get(3).toString()),
                new BigDecimal(klineArray.get(4).toString()),
                new BigDecimal(klineArray.get(5).toString()),
                new BigDecimal(klineArray.get(7).toString()),
                ((Number) klineArray.get(8)).intValue(),
                Instant.ofEpochMilli(((Number) klineArray.get(6)).longValue())
        );
    }
}
