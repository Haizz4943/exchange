package com.haizz.exchange.marketdata.api;

import com.haizz.exchange.marketdata.api.dto.DepthResponse;
import com.haizz.exchange.marketdata.api.dto.HealthResponse;
import com.haizz.exchange.marketdata.api.dto.PairMetadataResponse;
import com.haizz.exchange.marketdata.api.dto.TickerResponse;
import com.haizz.exchange.marketdata.application.health.FeedHealthMonitor;
import com.haizz.exchange.marketdata.application.query.GetDepthUseCase;
import com.haizz.exchange.marketdata.application.query.GetPairMetadataUseCase;
import com.haizz.exchange.marketdata.application.query.GetTickerUseCase;
import com.haizz.exchange.marketdata.infrastructure.cache.HealthRedisRepository;
import com.haizz.exchange.marketdata.shared.Constants;
import com.haizz.exchange.marketdata.shared.StartupState;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalController {

    private final GetTickerUseCase getTickerUseCase;
    private final GetDepthUseCase getDepthUseCase;
    private final GetPairMetadataUseCase getPairMetadataUseCase;
    private final FeedHealthMonitor feedHealthMonitor;
    private final HealthRedisRepository healthRepo;
    private final StartupState startupState;

    /** Called by Order Service — returns current best bid/ask for a pair */
    @GetMapping("/ticker/{pair}")
    public Mono<TickerResponse> ticker(@PathVariable String pair) {
        return getTickerUseCase.execute(pair).map(TickerResponse::from);
    }

    /** Called by Matching Engine — returns full depth for walk-the-book */
    @GetMapping("/depth/{pair}")
    public Mono<DepthResponse> depth(
            @PathVariable String pair,
            @RequestParam(defaultValue = "20") int levels) {
        return getDepthUseCase.execute(pair, Math.min(levels, Constants.MAX_DEPTH_LEVELS))
                .map(DepthResponse::from);
    }

    /** Called by Order Service — tick size, step size, min notional */
    @GetMapping("/pairs/{pair}/metadata")
    public Mono<PairMetadataResponse> pairMetadata(@PathVariable String pair) {
        return getPairMetadataUseCase.execute(pair).map(PairMetadataResponse::from);
    }

    /** Called by Matching Engine / Order Service — per-pair feed health */
    @GetMapping("/market-data/health")
    public Mono<HealthResponse> health() {
        return healthRepo.getWsStatus()
                .map(status -> HealthResponse.from(
                        feedHealthMonitor.getAllHealth(),
                        Constants.WS_STATUS_CONNECTED.equals(status)
                ));
    }
}
