package com.haizz.exchange.marketdata.application.backfill;

import com.haizz.exchange.marketdata.config.MarketProperties;
import com.haizz.exchange.marketdata.domain.Candlestick;
import com.haizz.exchange.marketdata.domain.Interval;
import com.haizz.exchange.marketdata.domain.PairSymbol;
import com.haizz.exchange.marketdata.infrastructure.persistence.CandlestickJdbcRepository;
import com.haizz.exchange.marketdata.infrastructure.provider.MarketDataProvider;
import com.haizz.exchange.marketdata.shared.Constants;
import com.haizz.exchange.marketdata.shared.StartupState;
import com.haizz.exchange.marketdata.shared.SupportedPairs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CandlestickBackfillService {

    private final MarketDataProvider provider;
    private final CandlestickJdbcRepository repository;
    private final SupportedPairs supportedPairs;
    private final MarketProperties properties;
    private final StartupState startupState;

    public Mono<Void> runStartupBackfill() {
        log.info("Starting candlestick backfill for {} pairs × {} intervals",
                supportedPairs.getPairs().size(), supportedPairs.getIntervals().size());

        return Flux.fromIterable(supportedPairs.getPairs())
                .flatMap(pair -> Flux.fromIterable(supportedPairs.getIntervals())
                        .flatMap(interval -> backfillOne(pair, interval))
                )
                .then()
                .doOnSuccess(v -> {
                    log.info("Backfill complete");
                    startupState.markBackfillComplete();
                })
                .doOnError(e -> log.error("Backfill failed: {}", e.getMessage()));
    }

    private Mono<Void> backfillOne(PairSymbol pair, Interval interval) {
        Instant targetStart = computeTargetStart(interval);
        return repository.findLatestOpenTime(pair, interval)
                .flatMap(opt -> {
                    Instant from = opt.orElse(targetStart);
                    Instant now = Instant.now();
                    if (!from.isBefore(now.minus(interval.getDuration()))) {
                        log.debug("No gap for {} {}", pair, interval.getValue());
                        return Mono.empty();
                    }
                    log.info("Backfilling {} {} from {} to {}", pair, interval.getValue(), from, now);
                    return fetchAndPersistInBatches(pair, interval, from, now);
                });
    }

    private Mono<Void> fetchAndPersistInBatches(PairSymbol pair, Interval interval, Instant from, Instant to) {
        return Flux.generate(
                        () -> from,
                        (cursor, sink) -> {
                            if (!cursor.isBefore(to)) {
                                sink.complete();
                                return cursor;
                            }
                            Instant batchEnd = cursor.plus(
                                    interval.getDuration().multipliedBy(Constants.BINANCE_MAX_BARS_PER_CALL));
                            if (batchEnd.isAfter(to)) batchEnd = to;
                            sink.next(new BatchRequest(cursor, batchEnd));
                            return batchEnd;
                        }
                )
                .cast(BatchRequest.class)
                .flatMap(req ->
                        provider.fetchKlines(pair, interval, req.from(), req.to(), Constants.BINANCE_MAX_BARS_PER_CALL)
                                .flatMap(repository::upsertAll)
                                .doOnSuccess(v -> log.debug("Persisted batch for {} {} [{} → {}]",
                                        pair, interval.getValue(), req.from(), req.to()))
                                .onErrorResume(e -> {
                                    log.error("Batch fetch failed for {} {}: {}", pair, interval.getValue(), e.getMessage());
                                    return Mono.empty();
                                })
                , 1) // sequential per pair+interval to respect rate limits
                .then();
    }

    @Scheduled(fixedDelayString = "${market.backfill.gap-check-interval-ms:300000}",
               initialDelay = 60_000)
    public void detectAndFillGaps() {
        if (!startupState.isBackfillComplete()) return;
        supportedPairs.getPairs().forEach(pair ->
                backfillOne(pair, Interval.ONE_MINUTE)
                        .subscribe(null, e -> log.error("Gap fill failed for {}: {}", pair, e.getMessage()))
        );
    }

    private Instant computeTargetStart(Interval interval) {
        int days = properties.getBackfill().getInitialHistory()
                .getOrDefault(interval.getValue(), 30);
        return Instant.now().minus(days, ChronoUnit.DAYS);
    }

    private record BatchRequest(Instant from, Instant to) {}
}
