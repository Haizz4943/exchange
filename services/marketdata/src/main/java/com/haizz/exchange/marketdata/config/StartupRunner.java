package com.haizz.exchange.marketdata.config;

import com.haizz.exchange.marketdata.application.backfill.CandlestickBackfillService;
import com.haizz.exchange.marketdata.application.exchangeinfo.ExchangeInfoSyncService;
import com.haizz.exchange.marketdata.infrastructure.provider.binance.BinanceMarketDataProvider;
import com.haizz.exchange.marketdata.infrastructure.provider.binance.BinanceWebSocketClient;
import com.haizz.exchange.marketdata.shared.SupportedPairs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class StartupRunner implements ApplicationRunner {

    private final ExchangeInfoSyncService exchangeInfoSyncService;
    private final CandlestickBackfillService backfillService;
    private final SupportedPairs supportedPairs;
    private final Optional<BinanceWebSocketClient> wsClient;
    private final Optional<BinanceMarketDataProvider> binanceProvider;

    @Override
    public void run(ApplicationArguments args) {
        log.info("=== Market Data Service startup sequence starting ===");

        log.info("Step 1/3: Syncing exchange info from provider...");
        exchangeInfoSyncService.runStartupSync()
                .onErrorResume(e -> {
                    log.error("Exchange info sync failed on startup — service will retry on schedule: {}", e.getMessage());
                    return reactor.core.publisher.Mono.empty();
                })
                .block(Duration.ofSeconds(30));

        log.info("Step 2/3: Running candlestick backfill...");
        backfillService.runStartupBackfill()
                .onErrorResume(e -> {
                    log.error("Backfill encountered errors — gaps will be filled by scheduled job: {}", e.getMessage());
                    return reactor.core.publisher.Mono.empty();
                })
                .block(Duration.ofMinutes(15));

        log.info("Step 3/3: Connecting WebSocket feed...");
        if (wsClient.isPresent() && binanceProvider.isPresent()) {
            var streamNames = binanceProvider.get().buildAllStreamNames(supportedPairs.getPairs());
            log.info("Subscribing to {} Binance streams", streamNames.size());
            wsClient.get().connect(streamNames);
        } else {
            log.warn("No WebSocket client available — real-time data feed will not start");
        }

        log.info("=== Market Data Service startup sequence complete ===");
    }
}
