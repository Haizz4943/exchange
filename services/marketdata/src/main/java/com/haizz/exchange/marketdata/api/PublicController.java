package com.haizz.exchange.marketdata.api;

import com.haizz.exchange.marketdata.api.dto.DepthResponse;
import com.haizz.exchange.marketdata.api.dto.ExchangeInfoResponse;
import com.haizz.exchange.marketdata.api.dto.TickerResponse;
import com.haizz.exchange.marketdata.application.query.GetDepthUseCase;
import com.haizz.exchange.marketdata.application.query.GetPairMetadataUseCase;
import com.haizz.exchange.marketdata.application.query.GetTickerUseCase;
import com.haizz.exchange.marketdata.shared.Constants;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/marketdata")
@RequiredArgsConstructor
public class PublicController {

    private final GetDepthUseCase getDepthUseCase;
    private final GetTickerUseCase getTickerUseCase;
    private final GetPairMetadataUseCase getPairMetadataUseCase;

    @GetMapping("/orderbook/{pair}")
    public Mono<DepthResponse> orderbook(
            @PathVariable String pair,
            @RequestParam(defaultValue = "20") int depth) {
        int levels = Math.min(depth, Constants.MAX_DEPTH_LEVELS);
        return getDepthUseCase.execute(pair, levels).map(DepthResponse::from);
    }

    @GetMapping("/ticker/{pair}")
    public Mono<TickerResponse> ticker(@PathVariable String pair) {
        return getTickerUseCase.execute(pair).map(TickerResponse::from);
    }

    @GetMapping("/exchangeInfo")
    public Mono<ExchangeInfoResponse> exchangeInfo() {
        return getPairMetadataUseCase.executeAll().map(ExchangeInfoResponse::from);
    }
}
