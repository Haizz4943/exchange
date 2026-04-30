package com.haizz.exchange.marketdata.infrastructure.provider;

import com.haizz.exchange.marketdata.domain.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface MarketDataProvider {

    String providerName();

    Flux<TradeObservation> streamTrades(Set<PairSymbol> pairs);

    Flux<DepthSnapshot> streamDepth(Set<PairSymbol> pairs);

    Flux<KlineUpdate> streamKlines(Set<PairSymbol> pairs, Set<Interval> intervals);

    Mono<List<Candlestick>> fetchKlines(PairSymbol pair, Interval interval,
                                        Instant from, Instant to, int limit);

    Mono<DepthSnapshot> fetchDepth(PairSymbol pair, int levels);

    Mono<Ticker> fetchTicker(PairSymbol pair);

    Mono<Map<PairSymbol, PairMetadata>> fetchExchangeInfo();
}
