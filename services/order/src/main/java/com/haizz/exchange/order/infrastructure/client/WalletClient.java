package com.haizz.exchange.order.infrastructure.client;

import com.haizz.exchange.order.config.AppProperties;
import com.haizz.exchange.order.domain.exception.InsufficientBalanceException;
import com.haizz.exchange.order.domain.exception.WalletUnavailableException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP client for the Wallet Service internal freeze/unfreeze endpoints.
 * These are stable internal contracts and are USED by the placement/cancel
 * use cases in a later phase.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WalletClient {

    private static final String REFERENCE_TYPE = "ORDER";

    private final AppProperties appProperties;
    private RestClient restClient;

    @PostConstruct
    void init() {
        this.restClient = RestClient.builder()
                .baseUrl(appProperties.clients().walletBaseUrl())
                .build();
    }

    public void freeze(UUID userId, String assetCode, BigDecimal amount, String referenceId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId.toString());
        body.put("assetCode", assetCode);
        body.put("amount", amount);
        body.put("referenceType", REFERENCE_TYPE);
        body.put("referenceId", referenceId);

        try {
            restClient.post()
                    .uri("/api/v1/wallets/internal/freeze")
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                        throw new InsufficientBalanceException(
                                "Wallet rejected freeze for userId=" + userId
                                        + " asset=" + assetCode + " amount=" + amount
                                        + " (status " + resp.getStatusCode() + ")");
                    })
                    .toBodilessEntity();
        } catch (InsufficientBalanceException e) {
            throw e;
        } catch (RestClientException e) {
            throw new WalletUnavailableException("Wallet freeze call failed", e);
        }
    }

    public void unfreeze(UUID userId, String assetCode, BigDecimal amount,
                         String referenceId, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId.toString());
        body.put("assetCode", assetCode);
        body.put("amount", amount);
        body.put("referenceType", REFERENCE_TYPE);
        body.put("referenceId", referenceId);
        body.put("reason", reason);

        try {
            restClient.post()
                    .uri("/api/v1/wallets/internal/unfreeze")
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                        throw new WalletUnavailableException(
                                "Wallet rejected unfreeze for userId=" + userId
                                        + " asset=" + assetCode + " (status "
                                        + resp.getStatusCode() + ")");
                    })
                    .toBodilessEntity();
        } catch (WalletUnavailableException e) {
            throw e;
        } catch (RestClientException e) {
            throw new WalletUnavailableException("Wallet unfreeze call failed", e);
        }
    }
}
