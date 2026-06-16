package com.haizz.exchange.order.application;

import com.haizz.exchange.common.enums.OrderSide;
import com.haizz.exchange.common.event.order.OrderCancelledEvent;
import com.haizz.exchange.common.event.order.OrderFilledEvent;
import com.haizz.exchange.common.event.order.OrderPartiallyFilledEvent;
import com.haizz.exchange.order.application.FillPersister.FillResult;
import com.haizz.exchange.order.application.FillPersister.Outcome;
import com.haizz.exchange.order.infrastructure.client.WalletClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the matching-events consumer use case (SR-042): delta conversion,
 * idempotency, terminal skips, and Order-owned residual release on terminal states.
 */
@ExtendWith(MockitoExtension.class)
class ProcessFillEventUseCaseTest {

    @Mock
    private FillPersister fillPersister;
    @Mock
    private WalletClient walletClient;
    @InjectMocks
    private ProcessFillEventUseCase useCase;

    private final UUID orderId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    private static FillResult applied(OrderSide side, String freeze, String asset,
                                      String filled, String avg) {
        return new FillResult(Outcome.APPLIED, UUID.randomUUID(), side,
                bd(freeze), asset, bd(filled), avg == null ? null : bd(avg));
    }

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    // ---- onPartiallyFilled -------------------------------------------------

    @Test
    @DisplayName("onPartiallyFilled passes the cumulative target to the persister; no unfreeze")
    void partialApplies() {
        var event = new OrderPartiallyFilledEvent(orderId, userId, "BTCUSDT",
                bd("0.04"), bd("0.06"), bd("55000"), Instant.now());
        when(fillPersister.applyPartial(eq(orderId), eq(bd("0.04")), eq(bd("55000"))))
                .thenReturn(applied(OrderSide.BUY, "5505.5", "USDT", "0.04", "55000"));

        useCase.onPartiallyFilled(event);

        verify(fillPersister).applyPartial(orderId, bd("0.04"), bd("55000"));
        // Partial fill is not terminal → never release residual.
        verify(walletClient, never()).unfreeze(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("onPartiallyFilled is idempotent: SKIPPED outcome → no unfreeze")
    void partialIdempotentSkip() {
        var event = new OrderPartiallyFilledEvent(orderId, userId, "BTCUSDT",
                bd("0.04"), bd("0.06"), bd("55000"), Instant.now());
        when(fillPersister.applyPartial(any(), any(), any()))
                .thenReturn(new FillResult(Outcome.SKIPPED, userId, OrderSide.BUY,
                        bd("5505.5"), "USDT", bd("0.04"), bd("55000")));

        useCase.onPartiallyFilled(event);

        verify(walletClient, never()).unfreeze(any(), any(), any(), any(), any());
    }

    // ---- onFilled ----------------------------------------------------------

    @Test
    @DisplayName("onFilled completes order and unfreezes BUY residual = freeze − filled×avg")
    void filledReleasesBuyResidual() {
        // BUY freeze 5505.5 (qty 0.1 @ 55000 + taker buffer). Fully filled 0.1 @ avg 55000.
        // consumed = 0.1 × 55000 = 5500. residual = 5505.5 − 5500 = 5.5.
        var event = new OrderFilledEvent(orderId, userId, "BTCUSDT",
                bd("0.10"), bd("55000"), Instant.now());
        FillResult result = applied(OrderSide.BUY, "5505.5", "USDT", "0.10", "55000");
        when(fillPersister.complete(eq(orderId), eq(bd("0.10")), eq(bd("55000"))))
                .thenReturn(result);

        useCase.onFilled(event);

        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        verify(walletClient).unfreeze(eq(result.userId()), eq("USDT"), amount.capture(),
                eq(orderId.toString()), eq("FILL_RESIDUAL"));
        assertEquals(0, bd("5.5").compareTo(amount.getValue()));
    }

    @Test
    @DisplayName("onFilled is idempotent: already FILLED (SKIPPED) → no unfreeze")
    void filledIdempotentSkip() {
        var event = new OrderFilledEvent(orderId, userId, "BTCUSDT",
                bd("0.10"), bd("55000"), Instant.now());
        when(fillPersister.complete(any(), any(), any()))
                .thenReturn(new FillResult(Outcome.SKIPPED, userId, OrderSide.BUY,
                        bd("5505.5"), "USDT", bd("0.10"), bd("55000")));

        useCase.onFilled(event);

        verify(walletClient, never()).unfreeze(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("onFilled SELL fully filled → residual 0 → no unfreeze call")
    void filledSellFullNoResidual() {
        // SELL freeze = qty (base). Fully filled → consumed = filled = freeze → residual 0.
        var event = new OrderFilledEvent(orderId, userId, "BTCUSDT",
                bd("0.05"), bd("60000"), Instant.now());
        when(fillPersister.complete(any(), any(), any()))
                .thenReturn(applied(OrderSide.SELL, "0.05", "BTC", "0.05", "60000"));

        useCase.onFilled(event);

        verify(walletClient, never()).unfreeze(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("onFilled MISSING order → no unfreeze (order not visible yet)")
    void filledMissingNoUnfreeze() {
        var event = new OrderFilledEvent(orderId, userId, "BTCUSDT",
                bd("0.10"), bd("55000"), Instant.now());
        when(fillPersister.complete(any(), any(), any()))
                .thenReturn(new FillResult(Outcome.MISSING, null, null, null, null, null, null));

        useCase.onFilled(event);

        verify(walletClient, never()).unfreeze(any(), any(), any(), any(), any());
    }

    // ---- onCancelled -------------------------------------------------------

    @Test
    @DisplayName("onCancelled REJECTED with 0 fills releases the FULL freezeAmount")
    void cancelledRejectedReleasesFull() {
        var event = new OrderCancelledEvent(orderId, userId, "BTCUSDT", "REJECTED", Instant.now());
        // 0 fills → consumed 0 → residual = full freeze 5505.5.
        FillResult result = applied(OrderSide.BUY, "5505.5", "USDT", "0", null);
        when(fillPersister.cancel(orderId)).thenReturn(result);

        useCase.onCancelled(event);

        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        verify(walletClient).unfreeze(eq(result.userId()), eq("USDT"), amount.capture(),
                eq(orderId.toString()), eq("FILL_RESIDUAL"));
        assertEquals(0, bd("5505.5").compareTo(amount.getValue()));
    }

    @Test
    @DisplayName("onCancelled MARKET_PARTIAL releases freeze − consumed (BUY)")
    void cancelledMarketPartialReleasesResidual() {
        var event = new OrderCancelledEvent(orderId, userId, "BTCUSDT",
                "MARKET_PARTIAL", Instant.now());
        // BUY freeze 5505.5, filled 0.04 @ avg 55000 → consumed 2200 → residual 3305.5.
        FillResult result = applied(OrderSide.BUY, "5505.5", "USDT", "0.04", "55000");
        when(fillPersister.cancel(orderId)).thenReturn(result);

        useCase.onCancelled(event);

        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        verify(walletClient).unfreeze(eq(result.userId()), eq("USDT"), amount.capture(),
                eq(orderId.toString()), eq("FILL_RESIDUAL"));
        assertEquals(0, bd("3305.5").compareTo(amount.getValue()));
    }

    @Test
    @DisplayName("onCancelled SELL MARKET_PARTIAL releases freeze − filled (base)")
    void cancelledSellPartialReleasesBase() {
        var event = new OrderCancelledEvent(orderId, userId, "BTCUSDT",
                "MARKET_PARTIAL", Instant.now());
        // SELL freeze 0.05 base, filled 0.02 → residual 0.03.
        FillResult result = applied(OrderSide.SELL, "0.05", "BTC", "0.02", "60000");
        when(fillPersister.cancel(orderId)).thenReturn(result);

        useCase.onCancelled(event);

        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        verify(walletClient).unfreeze(eq(result.userId()), eq("BTC"), amount.capture(),
                eq(orderId.toString()), eq("FILL_RESIDUAL"));
        assertEquals(0, bd("0.03").compareTo(amount.getValue()));
    }

    @Test
    @DisplayName("onCancelled already terminal (SKIPPED) → no unfreeze (guards double-release)")
    void cancelledSkippedNoUnfreeze() {
        var event = new OrderCancelledEvent(orderId, userId, "BTCUSDT", "REJECTED", Instant.now());
        when(fillPersister.cancel(orderId))
                .thenReturn(new FillResult(Outcome.SKIPPED, userId, OrderSide.BUY,
                        bd("5505.5"), "USDT", bd("0"), null));

        useCase.onCancelled(event);

        verify(walletClient, never()).unfreeze(any(), any(), any(), any(), any());
    }

    // ---- computeResidual ---------------------------------------------------

    @Test
    @DisplayName("computeResidual never negative (over-consumed clamps to 0)")
    void residualNeverNegative() {
        // Pathological: consumed > freeze → clamp to 0.
        FillResult result = applied(OrderSide.BUY, "100", "USDT", "0.01", "20000"); // consumed 200
        assertEquals(0, BigDecimal.ZERO.compareTo(
                ProcessFillEventUseCase.computeResidual(result)));
    }

    @Test
    @DisplayName("computeResidual rounds DOWN at 8 dp")
    void residualRoundsDown() {
        // freeze 10, BUY filled 0.000000003 @ 1 → consumed 0.000000003;
        // residual 9.999999997 → DOWN 8dp = 9.99999999.
        FillResult result = applied(OrderSide.BUY, "10", "USDT", "0.000000003", "1");
        assertEquals(0, new BigDecimal("9.99999999").compareTo(
                ProcessFillEventUseCase.computeResidual(result)));
    }
}
