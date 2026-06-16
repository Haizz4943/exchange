package com.haizz.exchange.matching.infrastructure.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.haizz.exchange.matching.config.AppProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * REST client for the Market Data service internal API.
 */
@Component
public class MarketDataClient {

    private final RestClient restClient;

    public MarketDataClient(AppProperties appProperties) {
        this.restClient = RestClient.builder()
                .baseUrl(appProperties.clients().marketdataBaseUrl())
                .build();
    }

    /** GET /internal/depth/{pair}?depth= */
    public DepthResponse getDepth(String pair, int depth) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/internal/depth/{pair}")
                        .queryParam("depth", depth)
                        .build(pair))
                .retrieve()
                .body(DepthResponse.class);
    }

    /** GET /internal/market-data/health */
    public HealthResponse getHealth() {
        return restClient.get()
                .uri("/internal/market-data/health")
                .retrieve()
                .body(HealthResponse.class);
    }

    /**
     * Depth snapshot. Bids/asks are arrays of [price, qty] string pairs.
     */
    public record DepthResponse(
            String pair,
            List<List<String>> bids,
            List<List<String>> asks,
            @JsonProperty("updated_at") String updatedAt
    ) {}

    /**
     * Per-pair feed health plus an overall status.
     */
    public record HealthResponse(
            Map<String, Object> pairs,
            @JsonProperty("overall_status") String overallStatus
    ) {}
}
