package com.haizz.exchange.matching.infrastructure.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.haizz.exchange.matching.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REST client for the Market Data service internal API.
 */
@Slf4j
@Component
public class MarketDataClient {

    private final RestClient restClient;

    /**
     * In-memory cache of pair metadata. Pair reference data (base/quote asset, tick/step
     * size) is effectively static, so we cache it to avoid a per-fill HTTP call.
     */
    private final Map<String, PairMetadataResponse> metadataCache = new ConcurrentHashMap<>();

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
     * GET /internal/pairs/{pair}/metadata — returns base/quote asset + sizing rules.
     *
     * <p>Cached in-memory after the first successful fetch. On failure, falls back to a
     * best-effort metadata derived from the pair symbol (quote = USDT for the known pairs)
     * so fee/trade-event resolution never blocks a fill; the failure is logged.
     */
    public PairMetadataResponse getPairMetadata(String pair) {
        PairMetadataResponse cached = metadataCache.get(pair);
        if (cached != null) {
            return cached;
        }
        try {
            PairMetadataResponse meta = restClient.get()
                    .uri("/internal/pairs/{pair}/metadata", pair)
                    .retrieve()
                    .body(PairMetadataResponse.class);
            if (meta != null) {
                metadataCache.put(pair, meta);
                return meta;
            }
            log.warn("Empty metadata for pair={}, using fallback", pair);
        } catch (Exception e) {
            log.warn("Failed to fetch metadata for pair={}, using fallback: {}", pair, e.toString());
        }
        return fallbackMetadata(pair);
    }

    /**
     * Best-effort metadata when the Market Data call fails. The 5 known pairs are quoted
     * in USDT; the base asset is the symbol with the quote suffix stripped.
     */
    private PairMetadataResponse fallbackMetadata(String pair) {
        String quote = "USDT";
        String base = pair.toUpperCase().endsWith(quote)
                ? pair.substring(0, pair.length() - quote.length())
                : pair;
        return new PairMetadataResponse(pair, base, quote, null, null, null, "UNKNOWN", null);
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

    /**
     * Pair reference data. JSON is snake_case (matches Market Data's
     * {@code PairMetadataResponse}).
     */
    public record PairMetadataResponse(
            String symbol,
            @JsonProperty("base_asset") String baseAsset,
            @JsonProperty("quote_asset") String quoteAsset,
            @JsonProperty("tick_size") String tickSize,
            @JsonProperty("step_size") String stepSize,
            @JsonProperty("min_notional") String minNotional,
            String status,
            @JsonProperty("updated_at") String updatedAt
    ) {}
}
