package com.haizz.exchange.gateway.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for WsMessageRouter.resolveRouting — no infrastructure required.
 * Tests channel/schema/userId/payload resolution per DECISIONS.md §2.
 */
class WsMessageRouterTest {

    private WsMessageRouter router;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // Pass null for registry/subscriptionManager since we only test resolveRouting
        router = new WsMessageRouter(null, null, objectMapper);
    }

    @Test
    void depthTopic_routesToMarketDepthChannel() throws Exception {
        String json = """
                {"pair":"BTCUSDT","bids":[[\"29000\",\"1.5\"]],"asks":[[\"29001\",\"0.5\"]],"updatedAt":"2026-06-01T10:00:00Z"}
                """;
        var raw = objectMapper.readTree(json);
        var routing = router.resolveRouting("market-data.depth.v1", raw);

        assertThat(routing).isNotNull();
        assertThat(routing.channel()).isEqualTo("market:BTCUSDT:depth");
        assertThat(routing.schema()).isEqualTo("market-data.depth.v1");
        assertThat(routing.userId()).isNull(); // broadcast
    }

    @Test
    void klineTopic_routesToMarketKlineChannel_withEpochTime() throws Exception {
        String json = """
                {"pair":"BTCUSDT","interval":"1m","openTime":"2026-06-01T10:00:00Z",
                 "open":"29000","high":"29100","low":"28900","close":"29050",
                 "volume":"100.5","closed":false}
                """;
        var raw = objectMapper.readTree(json);
        var routing = router.resolveRouting("market-data.kline.v1", raw);

        assertThat(routing).isNotNull();
        assertThat(routing.channel()).isEqualTo("market:BTCUSDT:kline:1m");
        assertThat(routing.schema()).isEqualTo("market-data.kline.v1");
        assertThat(routing.userId()).isNull();

        @SuppressWarnings("unchecked")
        var payload = (java.util.Map<String, Object>) routing.payload();
        // time should be epoch seconds (not ISO string)
        assertThat(payload.get("time")).isInstanceOf(Long.class);
        assertThat((Long) payload.get("time")).isGreaterThan(0L);
    }

    @Test
    void marketDataEventsTopic_routesToTradesChannel_withSideTransform() throws Exception {
        String json = """
                {"eventId":"e1","eventType":"ExternalTradeObservedEvent","version":1,
                 "payload":{"pair":"BTCUSDT","price":"29000","quantity":"0.5",
                            "buyerIsMaker":true,"externalTradeId":"t1","eventTime":"2026-06-01T10:00:05Z"}}
                """;
        var raw = objectMapper.readTree(json);
        var routing = router.resolveRouting("market-data.events.v1", raw);

        assertThat(routing).isNotNull();
        assertThat(routing.channel()).isEqualTo("market:BTCUSDT:trades");
        // NOTE: schema has no "Event" suffix — matches FE WsStoreSyncer.tsx contract
        assertThat(routing.schema()).isEqualTo("market-data.events.v1.ExternalTradeObserved");
        assertThat(routing.userId()).isNull();

        @SuppressWarnings("unchecked")
        var payload = (java.util.Map<String, Object>) routing.payload();
        // buyerIsMaker=true → SELL (aggressive side is sell)
        assertThat(payload.get("side")).isEqualTo("SELL");
        assertThat(payload.get("executedAt")).isEqualTo("2026-06-01T10:00:05Z");
    }

    @Test
    void walletTransactionTopic_routesToWalletChannel_userScoped() throws Exception {
        String json = """
                {"txnId":"tx1","walletId":"w1","userId":"user-abc","assetCode":"USDT",
                 "type":"DEPOSIT","deltaAvailable":"100.00","deltaFrozen":"0","deltaTotal":"100.00"}
                """;
        var raw = objectMapper.readTree(json);
        var routing = router.resolveRouting("wallet.transactions.v1", raw);

        assertThat(routing).isNotNull();
        assertThat(routing.channel()).isEqualTo("wallet");
        assertThat(routing.schema()).isEqualTo("wallet.events.v1.WalletTransactionRecorded");
        // user-scoped: only delivered to this user
        assertThat(routing.userId()).isEqualTo("user-abc");
    }

    @Test
    void matchingEventsTopic_routesToOrdersChannel_userScoped() throws Exception {
        String json = """
                {"eventType":"OrderPartiallyFilled","version":1,
                 "payload":{"userId":"user-xyz","orderId":"o1","filledQty":"0.5"}}
                """;
        var raw = objectMapper.readTree(json);
        var routing = router.resolveRouting("matching.events.v1", raw);

        assertThat(routing).isNotNull();
        assertThat(routing.channel()).isEqualTo("orders");
        assertThat(routing.schema()).isEqualTo("matching.events.v1.OrderPartiallyFilled");
        assertThat(routing.userId()).isEqualTo("user-xyz");
    }

    @Test
    void unknownTopic_returnsNull() throws Exception {
        var raw = objectMapper.readTree("{}");
        assertThat(router.resolveRouting("unknown.topic.v99", raw)).isNull();
    }

    @Test
    void buyerIsMakerFalse_sideIsBuy() throws Exception {
        String json = """
                {"eventId":"e1","eventType":"ExternalTradeObservedEvent","version":1,
                 "payload":{"pair":"ETHUSDT","price":"2000","quantity":"1.0",
                            "buyerIsMaker":false,"externalTradeId":"t2","eventTime":"2026-06-01T10:00:10Z"}}
                """;
        var raw = objectMapper.readTree(json);
        var routing = router.resolveRouting("market-data.events.v1", raw);

        @SuppressWarnings("unchecked")
        var payload = (java.util.Map<String, Object>) routing.payload();
        assertThat(payload.get("side")).isEqualTo("BUY");
    }
}
