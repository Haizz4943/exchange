package com.haizz.exchange.marketdata.application.query;

import com.haizz.exchange.marketdata.domain.PairMetadata;
import com.haizz.exchange.marketdata.domain.PairSymbol;
import com.haizz.exchange.marketdata.domain.exception.PairNotSupportedException;
import com.haizz.exchange.marketdata.infrastructure.cache.ExchangeInfoRedisRepository;
import com.haizz.exchange.marketdata.shared.SupportedPairs;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@Service
@RequiredArgsConstructor
public class GetPairMetadataUseCase {

    private final ExchangeInfoRedisRepository cache;
    private final SupportedPairs supportedPairs;

    public Mono<PairMetadata> execute(String symbol) {
        if (!supportedPairs.supports(symbol)) {
            return Mono.error(new PairNotSupportedException(symbol));
        }
        return cache.get(PairSymbol.of(symbol))
                .flatMap(opt -> opt
                        .map(Mono::just)
                        .orElseGet(() -> Mono.error(new PairNotSupportedException(symbol)))
                );
    }

    public Mono<List<PairMetadata>> executeAll() {
        return Flux.fromIterable(supportedPairs.getPairs())
                .flatMap(pair -> cache.get(pair).mapNotNull(opt -> opt.orElse(null)))
                .collectList();
    }
}
