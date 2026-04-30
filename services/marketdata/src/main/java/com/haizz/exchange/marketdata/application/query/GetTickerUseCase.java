package com.haizz.exchange.marketdata.application.query;

import com.haizz.exchange.marketdata.domain.PairSymbol;
import com.haizz.exchange.marketdata.domain.Ticker;
import com.haizz.exchange.marketdata.domain.exception.PairNotSupportedException;
import com.haizz.exchange.marketdata.domain.exception.TickerUnavailableException;
import com.haizz.exchange.marketdata.infrastructure.cache.TickerRedisRepository;
import com.haizz.exchange.marketdata.shared.SupportedPairs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class GetTickerUseCase {

    private final TickerRedisRepository tickerCache;
    private final SupportedPairs supportedPairs;

    public Mono<Ticker> execute(String symbol) {
        if (!supportedPairs.supports(symbol)) {
            return Mono.error(new PairNotSupportedException(symbol));
        }
        var pair = PairSymbol.of(symbol);
        return tickerCache.get(pair)
                .flatMap(opt -> opt
                        .map(Mono::just)
                        .orElseGet(() -> {
                            log.warn("Ticker cache miss for {}", symbol);
                            return Mono.error(new TickerUnavailableException(symbol));
                        })
                );
    }
}
