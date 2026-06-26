package com.haizz.exchange.order.infrastructure.outbox;

import com.haizz.exchange.common.enums.OrderSide;
import com.haizz.exchange.common.enums.OrderType;
import com.haizz.exchange.order.config.AppProperties;
import com.haizz.exchange.order.config.AppProperties.KafkaTopicProperties;
import com.haizz.exchange.order.config.AppProperties.OutboxProperties;
import com.haizz.exchange.order.domain.Order;
import com.haizz.exchange.order.domain.OrderOutbox;
import com.haizz.exchange.order.domain.OrderState;
import com.haizz.exchange.order.infrastructure.persistence.OrderOutboxRepository;
import com.haizz.exchange.order.infrastructure.persistence.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the outbox relay's NEW → OPEN self-transition on publish-ack
 * (SRS_Appendix_OrderService §5 / SR-ORDER-EDGE-006). The publish path itself is
 * exercised via a completed Kafka future so {@code send(...).get()} returns cleanly.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderOutboxRelayTest {

    @Mock
    private OrderOutboxRepository outboxRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;
    @Mock
    private AppProperties appProperties;
    @InjectMocks
    private OrderOutboxRelay relay;

    @BeforeEach
    void setUp() {
        when(appProperties.outbox()).thenReturn(new OutboxProperties(100, 50));
        when(appProperties.kafka())
                .thenReturn(new KafkaTopicProperties("orders.events.v1", "matching.events.v1"));
        CompletableFuture<SendResult<String, String>> done = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(done);
    }

    private static Order newOrder(OrderState state) {
        Order o = Order.newOrder(UUID.randomUUID(), null, "BTCUSDT",
                OrderSide.BUY, OrderType.LIMIT, new BigDecimal("0.1"),
                new BigDecimal("50000"), "GTC", new BigDecimal("5000"), "USDT");
        o.setState(state);
        return o;
    }

    private void pending(OrderOutbox event) {
        when(outboxRepository.findPendingEvents(any(Pageable.class))).thenReturn(List.of(event));
    }

    @Test
    @DisplayName("OrderPlaced publish-ack transitions a NEW order to OPEN and saves it")
    void orderPlacedMarksOpen() {
        Order order = newOrder(OrderState.NEW);
        OrderOutbox event = OrderOutbox.of("OrderPlaced", order.getId().toString(), "{}");
        pending(event);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        relay.relay();

        assertEquals(OrderState.OPEN, order.getState());
        verify(orderRepository).save(order);
    }

    @Test
    @DisplayName("OrderPlaced is skipped (no throw, no save) when the order has left NEW")
    void orderPlacedSkipsNonNew() {
        Order order = newOrder(OrderState.CANCEL_REQUESTED);
        OrderOutbox event = OrderOutbox.of("OrderPlaced", order.getId().toString(), "{}");
        pending(event);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        relay.relay();

        assertEquals(OrderState.CANCEL_REQUESTED, order.getState());
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("OrderCancelled publish does not touch order state")
    void orderCancelledDoesNotMarkOpen() {
        OrderOutbox event = OrderOutbox.of("OrderCancelled", UUID.randomUUID().toString(), "{}");
        pending(event);

        relay.relay();

        verify(orderRepository, never()).findById(any());
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("OrderPlaced whose order is missing logs but does not save")
    void orderPlacedMissingOrder() {
        UUID orderId = UUID.randomUUID();
        OrderOutbox event = OrderOutbox.of("OrderPlaced", orderId.toString(), "{}");
        pending(event);
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        relay.relay();

        verify(orderRepository, never()).save(any());
        // Still marked published so it is not retried forever.
        verify(outboxRepository).save(event);
    }

    @Test
    @DisplayName("Failed publish never marks the order OPEN")
    void failedPublishLeavesOrderNew() {
        Order order = newOrder(OrderState.NEW);
        OrderOutbox event = OrderOutbox.of("OrderPlaced", order.getId().toString(), "{}");
        pending(event);
        CompletableFuture<SendResult<String, String>> boom = new CompletableFuture<>();
        boom.completeExceptionally(new RuntimeException("kafka down"));
        when(kafkaTemplate.send(eq("orders.events.v1"), anyString(), anyString())).thenReturn(boom);

        relay.relay();

        assertEquals(OrderState.NEW, order.getState());
        verify(orderRepository, never()).findById(any());
        verify(orderRepository, never()).save(any());
    }
}
