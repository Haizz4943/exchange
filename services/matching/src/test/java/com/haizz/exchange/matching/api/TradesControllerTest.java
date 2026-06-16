package com.haizz.exchange.matching.api;

import com.haizz.exchange.common.enums.OrderSide;
import com.haizz.exchange.common.enums.TradeRole;
import com.haizz.exchange.matching.api.dto.PageResponse;
import com.haizz.exchange.matching.api.dto.TradeResponse;
import com.haizz.exchange.matching.domain.Trade;
import com.haizz.exchange.matching.infrastructure.persistence.TradeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit test for {@link TradesController}: maps repo results to the snake_case
 * {@link PageResponse}, derives userId from the JWT subject, and clamps page size. No Spring MVC.
 */
@ExtendWith(MockitoExtension.class)
class TradesControllerTest {

    @Mock
    private TradeRepository tradeRepository;
    @Mock
    private Jwt jwt;

    private static Trade trade(UUID userId) {
        return Trade.of(UUID.randomUUID(), userId, "BTCUSDT", "BTC", "USDT", OrderSide.BUY,
                new BigDecimal("60000"), new BigDecimal("1"), new BigDecimal("60000"),
                new BigDecimal("0.001"), "BTC", TradeRole.TAKER, null, Instant.now());
    }

    @Test
    void returnsCallersTrades_mappedToSnakeCaseResponse() {
        UUID userId = UUID.randomUUID();
        when(jwt.getSubject()).thenReturn(userId.toString());
        Trade t = trade(userId);
        when(tradeRepository.findByUserIdOrderByExecutedAtDesc(eq(userId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(t), PageRequest.of(0, 50), 1));

        TradesController controller = new TradesController(tradeRepository);
        ResponseEntity<PageResponse<TradeResponse>> resp = controller.getMyTrades(jwt, 0, 50);

        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().content()).hasSize(1);
        TradeResponse tr = resp.getBody().content().get(0);
        assertThat(tr.id()).isEqualTo(t.getId());
        assertThat(tr.side()).isEqualTo("BUY");
        assertThat(tr.role()).isEqualTo("TAKER");
        assertThat(tr.price()).isEqualByComparingTo("60000");
        assertThat(resp.getBody().totalElements()).isEqualTo(1);
        assertThat(resp.getBody().first()).isTrue();
        assertThat(resp.getBody().last()).isTrue();
    }

    @Test
    void clampsPageSizeToMax200() {
        UUID userId = UUID.randomUUID();
        when(jwt.getSubject()).thenReturn(userId.toString());
        when(tradeRepository.findByUserIdOrderByExecutedAtDesc(eq(userId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        TradesController controller = new TradesController(tradeRepository);
        controller.getMyTrades(jwt, 0, 5000);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(tradeRepository).findByUserIdOrderByExecutedAtDesc(eq(userId), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(200);
    }
}
