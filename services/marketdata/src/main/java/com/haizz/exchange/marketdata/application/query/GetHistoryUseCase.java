package com.haizz.exchange.marketdata.application.query;

import com.haizz.exchange.marketdata.domain.Candlestick;
import com.haizz.exchange.marketdata.domain.Interval;
import com.haizz.exchange.marketdata.domain.PairSymbol;
import com.haizz.exchange.marketdata.domain.exception.PairNotSupportedException;
import com.haizz.exchange.marketdata.domain.exception.RangeTooLargeException;
import com.haizz.exchange.marketdata.infrastructure.persistence.CandlestickJdbcRepository;
import com.haizz.exchange.marketdata.shared.Constants;
import com.haizz.exchange.marketdata.shared.SupportedPairs;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class GetHistoryUseCase {

    private final CandlestickJdbcRepository repository;
    private final SupportedPairs supportedPairs;

    public Mono<List<Candlestick>> execute(String symbol, Interval interval,
                                           Instant from, Instant to, Integer countback) {
        if (!supportedPairs.supports(symbol)) {
            return Mono.error(new PairNotSupportedException(symbol));
        }

        // Honour countback hint (newer UDF clients send this)
        Instant effectiveFrom = from;
        if (countback != null && countback > 0) {
            Instant countbackFrom = to.minus(interval.getDuration().multipliedBy(countback));
            effectiveFrom = from.isAfter(countbackFrom) ? from : countbackFrom;
        }

        long barCount = (to.toEpochMilli() - effectiveFrom.toEpochMilli())
                / interval.getDuration().toMillis() + 1;
        if (barCount > Constants.MAX_BARS_PER_REQUEST) {
            return Mono.error(new RangeTooLargeException((int) barCount, Constants.MAX_BARS_PER_REQUEST));
        }

        return repository.findHistory(PairSymbol.of(symbol), interval, effectiveFrom, to,
                Constants.MAX_BARS_PER_REQUEST);
    }

    public Mono<Instant> findEarliestBar(String symbol, Interval interval) {
        return repository.findEarliestOpenTime(PairSymbol.of(symbol), interval)
                .map(opt -> opt.orElse(Instant.EPOCH));
    }
}
