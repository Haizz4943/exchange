package com.haizz.exchange.matching.infrastructure.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.haizz.exchange.matching.config.AppProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * REST client for the Order service internal projection API.
 */
@Component
public class OrderClient {

    private final RestClient restClient;

    public OrderClient(AppProperties appProperties) {
        this.restClient = RestClient.builder()
                .baseUrl(appProperties.clients().orderBaseUrl())
                .build();
    }

    /**
     * GET /api/v1/orders/internal/orders?state=OPEN,PARTIALLY_FILLED&page=&size=
     * Used to rebuild the in-memory open-orders index on startup / recovery.
     */
    public PagedOrders fetchOpenOrders(int page, int size) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/orders/internal/orders")
                        .queryParam("state", "OPEN,PARTIALLY_FILLED")
                        .queryParam("page", page)
                        .queryParam("size", size)
                        .build())
                .retrieve()
                .body(new ParameterizedTypeReference<PagedOrders>() {});
    }

    /** Internal order projection (camelCase, per Order service). */
    public record InternalOrderDto(
            UUID id,
            UUID userId,
            String pair,
            String side,
            String type,
            BigDecimal quantity,
            BigDecimal limitPrice,
            BigDecimal filledQuantity,
            Instant createdAt
    ) {}

    public record PagedOrders(
            List<InternalOrderDto> content,
            int page,
            int size,
            @JsonProperty("total_elements") long totalElements,
            @JsonProperty("total_pages") int totalPages
    ) {}
}
