package com.haizz.exchange.order.infrastructure.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.haizz.exchange.order.config.AppProperties;
import com.haizz.exchange.order.domain.exception.MarketDataUnavailableException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * HTTP client for the Market Data Service ticker endpoint. Used to obtain a
 * reference price for MARKET orders in a later phase.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketDataClient {

    private final AppProperties appProperties;
    private RestClient restClient;

    @PostConstruct
    void init() {
        this.restClient = RestClient.builder()
                .baseUrl(appProperties.clients().marketdataBaseUrl())
                .build();
    }

    public Ticker getTicker(String pair) {
        try {
            Ticker ticker = restClient.get()
                    .uri("/api/v1/marketdata/ticker/{pair}", pair)
                    .retrieve()
                    .body(Ticker.class);
            if (ticker == null) {
                throw new MarketDataUnavailableException(
                        "Empty ticker response for pair=" + pair);
            }
            return ticker;
        } catch (RestClientException e) {
            throw new MarketDataUnavailableException(
                    "Market data ticker call failed for pair=" + pair, e);
        }
    }

    public record Ticker(
            String pair,
            @JsonProperty("best_bid") BigDecimal bestBid,
            @JsonProperty("best_ask") BigDecimal bestAsk,
            @JsonProperty("last_price") BigDecimal lastPrice,
            @JsonProperty("updated_at") Instant updatedAt
    ) {}
}
