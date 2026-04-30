package com.haizz.exchange.marketdata.infrastructure.provider.binance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizz.exchange.marketdata.domain.*;
import com.haizz.exchange.marketdata.infrastructure.provider.MarketDataProvider;
import com.haizz.exchange.marketdata.infrastructure.provider.binance.dto.BinanceDepthEvent;
import com.haizz.exchange.marketdata.infrastructure.provider.binance.dto.BinanceKlineEvent;
import com.haizz.exchange.marketdata.infrastructure.provider.binance.dto.BinanceTradeEvent;
import com.haizz.exchange.marketdata.infrastructure.provider.binance.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@ConditionalOnProperty(name = "market.data.provider", havingValue = "binance", matchIfMissing = true)
@RequiredArgsConstructor
public class BinanceMarketDataProvider implements MarketDataProvider {

    private final BinanceRestClient rest;
    private final BinanceWebSocketClient ws;
    private final ObjectMapper objectMapper;

    @Override
    public String providerName() { return "binance"; }

    @Override
    public Flux<TradeObservation> streamTrades(Set<PairSymbol> pairs) {
        var streamNames = pairs.stream()
                .map(p -> p.toLowerCaseStream("@trade"))
                .collect(Collectors.toSet());
        return ws.combinedStream(streamNames)
                .flatMap(node -> parseTrade(node, streamNames));
    }

    private Mono<TradeObservation> parseTrade(JsonNode node, Set<String> streamNames) {
        try {
            var event = objectMapper.treeToValue(node, BinanceTradeEvent.class);
            var obs = BinanceTradeMapper.toObservation(event);
            if (!obs.isValid()) {
                log.warn("Dropping invalid trade event for {}: price={} qty={}", obs.pair(), obs.price(), obs.quantity());
                return Mono.empty();
            }
            return Mono.just(obs);
        } catch (Exception e) {
            log.warn("Failed to parse trade event: {}", e.getMessage());
            return Mono.empty();
        }
    }

    @Override
    public Flux<DepthSnapshot> streamDepth(Set<PairSymbol> pairs) {
        var streamNames = pairs.stream()
                .map(p -> p.toLowerCaseStream("@depth20@100ms"))
                .collect(Collectors.toSet());
        return ws.combinedStream(streamNames)
                .flatMap(node -> parseDepth(node));
    }

    private Mono<DepthSnapshot> parseDepth(JsonNode node) {
        try {
            var event = objectMapper.treeToValue(node, BinanceDepthEvent.class);
            return Mono.just(BinanceDepthMapper.toSnapshot(event));
        } catch (Exception e) {
            log.warn("Failed to parse depth event: {}", e.getMessage());
            return Mono.empty();
        }
    }

    @Override
    public Flux<KlineUpdate> streamKlines(Set<PairSymbol> pairs, Set<Interval> intervals) {
        var streamNames = pairs.stream()
                .flatMap(p -> intervals.stream()
                        .map(i -> p.toLowerCaseStream("@kline_" + i.getValue())))
                .collect(Collectors.toSet());
        return ws.combinedStream(streamNames)
                .flatMap(node -> parseKline(node));
    }

    private Mono<KlineUpdate> parseKline(JsonNode node) {
        try {
            var event = objectMapper.treeToValue(node, BinanceKlineEvent.class);
            return Mono.just(BinanceKlineMapper.toKlineUpdate(event));
        } catch (Exception e) {
            log.warn("Failed to parse kline event: {}", e.getMessage());
            return Mono.empty();
        }
    }

    @Override
    public Mono<List<Candlestick>> fetchKlines(PairSymbol pair, Interval interval,
                                               Instant from, Instant to, int limit) {
        return rest.getKlines(pair.value(), interval.getValue(),
                        from.toEpochMilli(), to.toEpochMilli(), limit)
                .map(rawList -> rawList.stream()
                        .map(row -> BinanceKlineMapper.fromKlineArray(pair.value(), interval.getValue(), row))
                        .toList());
    }

    @Override
    public Mono<DepthSnapshot> fetchDepth(PairSymbol pair, int levels) {
        return rest.getExchangeInfo()
                .flatMap(info -> Mono.empty()); // simplified: use WS data; REST depth needs separate endpoint
    }

    @Override
    public Mono<Ticker> fetchTicker(PairSymbol pair) {
        return Mono.empty(); // derived from depth cache — handled in GetTickerUseCase
    }

    @Override
    public Mono<Map<PairSymbol, PairMetadata>> fetchExchangeInfo() {
        return rest.getExchangeInfo()
                .map(info -> BinanceExchangeInfoMapper.toMetadataMap(info,
                        info.symbols().stream()
                                .map(s -> PairSymbol.of(s.symbol()))
                                .collect(Collectors.toSet())));
    }

    public Set<String> buildAllStreamNames(Set<PairSymbol> pairs) {
        Set<String> streams = new HashSet<>();
        pairs.forEach(p -> {
            streams.add(p.toLowerCaseStream("@trade"));
            streams.add(p.toLowerCaseStream("@depth20@100ms"));
        });
        return streams;
    }
}
