package com.haizz.exchange.marketdata.application.query;

import com.haizz.exchange.marketdata.domain.DepthSnapshot;
import com.haizz.exchange.marketdata.domain.PairSymbol;
import com.haizz.exchange.marketdata.domain.exception.DepthUnavailableException;
import com.haizz.exchange.marketdata.domain.exception.PairNotSupportedException;
import com.haizz.exchange.marketdata.infrastructure.cache.DepthRedisRepository;
import com.haizz.exchange.marketdata.infrastructure.provider.MarketDataProvider;
import com.haizz.exchange.marketdata.shared.Constants;
import com.haizz.exchange.marketdata.shared.SupportedPairs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class GetDepthUseCase {

    private final DepthRedisRepository depthCache;
    private final MarketDataProvider provider;
    private final SupportedPairs supportedPairs;

    public Mono<DepthSnapshot> execute(String symbol) {
        return execute(symbol, Constants.DEFAULT_DEPTH_LEVELS);
    }

    public Mono<DepthSnapshot> execute(String symbol, int levels) {
        if (!supportedPairs.supports(symbol)) {
            return Mono.error(new PairNotSupportedException(symbol));
        }
        var pair = PairSymbol.of(symbol);

        return depthCache.get(pair)
                .flatMap(opt -> opt
                        .map(Mono::just)
                        .orElseGet(() -> {
                            log.warn("Depth cache miss for {}, falling back to provider", symbol);
                            return provider.fetchDepth(pair, levels)
                                    .switchIfEmpty(Mono.error(new DepthUnavailableException(symbol)));
                        })
                )
                .onErrorMap(e -> !(e instanceof DepthUnavailableException) && !(e instanceof PairNotSupportedException),
                        e -> new DepthUnavailableException(symbol));
    }
}
