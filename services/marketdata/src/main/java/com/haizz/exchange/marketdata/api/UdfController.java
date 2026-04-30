package com.haizz.exchange.marketdata.api;

import com.haizz.exchange.marketdata.api.dto.UdfConfigResponse;
import com.haizz.exchange.marketdata.api.dto.UdfHistoryResponse;
import com.haizz.exchange.marketdata.api.dto.UdfSymbolInfoResponse;
import com.haizz.exchange.marketdata.application.query.GetHistoryUseCase;
import com.haizz.exchange.marketdata.application.query.GetPairMetadataUseCase;
import com.haizz.exchange.marketdata.domain.exception.InvalidResolutionException;
import com.haizz.exchange.marketdata.domain.exception.PairNotSupportedException;
import com.haizz.exchange.marketdata.domain.exception.RangeTooLargeException;
import com.haizz.exchange.marketdata.shared.ResolutionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;

@Slf4j
@RestController
@RequestMapping("/udf")
@RequiredArgsConstructor
public class UdfController {

    private final GetHistoryUseCase getHistoryUseCase;
    private final GetPairMetadataUseCase getPairMetadataUseCase;

    @GetMapping("/config")
    public Mono<UdfConfigResponse> config() {
        return Mono.just(UdfConfigResponse.defaults());
    }

    @GetMapping("/symbols")
    public Mono<UdfSymbolInfoResponse> symbols(@RequestParam String symbol) {
        return getPairMetadataUseCase.execute(symbol)
                .map(UdfSymbolInfoResponse::from);
    }

    @GetMapping("/history")
    public Mono<UdfHistoryResponse> history(
            @RequestParam String symbol,
            @RequestParam String resolution,
            @RequestParam long from,
            @RequestParam long to,
            @RequestParam(required = false) Integer countback) {

        if (!com.haizz.exchange.marketdata.domain.Interval.isValidTvResolution(resolution)) {
            return Mono.just(UdfHistoryResponse.error("Invalid resolution: " + resolution));
        }

        var interval = ResolutionMapper.toInterval(resolution);
        var fromInstant = Instant.ofEpochSecond(from);
        var toInstant = Instant.ofEpochSecond(to);

        return getHistoryUseCase.execute(symbol, interval, fromInstant, toInstant, countback)
                .flatMap(bars -> {
                    if (bars.isEmpty()) {
                        return getHistoryUseCase.findEarliestBar(symbol, interval)
                                .map(earliest -> UdfHistoryResponse.noData(earliest.getEpochSecond()));
                    }
                    return Mono.just(UdfHistoryResponse.ok(bars));
                })
                .onErrorResume(PairNotSupportedException.class,
                        e -> Mono.just(UdfHistoryResponse.error("unknown_symbol")))
                .onErrorResume(RangeTooLargeException.class,
                        e -> Mono.just(UdfHistoryResponse.error(e.getMessage())))
                .onErrorResume(e -> {
                    log.error("UDF history error for {}: {}", symbol, e.getMessage());
                    return Mono.just(UdfHistoryResponse.error("Failed to load history"));
                });
    }
}
