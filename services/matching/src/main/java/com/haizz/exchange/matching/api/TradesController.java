package com.haizz.exchange.matching.api;

import com.haizz.exchange.matching.api.dto.PageResponse;
import com.haizz.exchange.matching.api.dto.TradeResponse;
import com.haizz.exchange.matching.domain.Trade;
import com.haizz.exchange.matching.infrastructure.persistence.TradeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Read API for the caller's executed trades (SR-058 — trade history). JWT-authenticated;
 * the user id is taken from {@code jwt.getSubject()}. Newest first.
 */
@RestController
@RequestMapping("/api/v1/trades")
@RequiredArgsConstructor
public class TradesController {

    private static final int MAX_SIZE = 200;

    private final TradeRepository tradeRepository;

    @GetMapping
    public ResponseEntity<PageResponse<TradeResponse>> getMyTrades(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        UUID userId = UUID.fromString(jwt.getSubject());
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_SIZE));
        Page<Trade> trades = tradeRepository.findByUserIdOrderByExecutedAtDesc(userId, pageable);
        return ResponseEntity.ok(PageResponse.of(trades, TradeResponse::from));
    }
}
