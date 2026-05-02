package com.haizz.exchange.marketdata.infrastructure.provider.binance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizz.exchange.marketdata.config.BinanceProperties;
import com.haizz.exchange.marketdata.shared.Constants;
import com.haizz.exchange.marketdata.shared.StartupState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
public class BinanceWebSocketClient {

    private final BinanceProperties props;
    private final ObjectMapper objectMapper;
    private final StartupState startupState;
    private final ReactiveStringRedisTemplate redis;
    private final Set<String> allStreamNames;

    private final ReactorNettyWebSocketClient wsClient = new ReactorNettyWebSocketClient();
    private final Sinks.Many<JsonNode> inbound = Sinks.many().multicast().onBackpressureBuffer(65_536);
    private final AtomicReference<Disposable> connection = new AtomicReference<>();
    private final AtomicInteger backoffAttempt = new AtomicInteger(0);

    public BinanceWebSocketClient(BinanceProperties props,
                                  ObjectMapper objectMapper,
                                  StartupState startupState,
                                  ReactiveStringRedisTemplate redis) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.startupState = startupState;
        this.redis = redis;
        this.allStreamNames = Set.of();
    }

    public void connect(Set<String> streamNames) {
        log.info("Connecting to Binance WS with {} streams", streamNames.size());
        var url = props.getWs().getBaseUrl() + "/stream?streams=" + String.join("/", streamNames);
        doConnect(url, streamNames);
    }

    private void doConnect(String url, Set<String> streamNames) {
        var handler = (org.springframework.web.reactive.socket.WebSocketHandler) session -> {
            log.info("Binance WS connected");
            backoffAttempt.set(0);
            startupState.markWsConnected();
            setWsStatus(Constants.WS_STATUS_CONNECTED);

            return session.receive()
                    .map(WebSocketMessage::getPayloadAsText)
                    .flatMap(this::parseJson)
                    .doOnNext(node -> inbound.tryEmitNext(node))
                    .doOnError(err -> log.warn("WS stream error: {}", err.getMessage()))
                    .then();
        };

        var disposable = wsClient.execute(URI.create(url), handler)
                .doOnError(err -> scheduleReconnect(err, url, streamNames))
                .doOnTerminate(() -> scheduleReconnect(null, url, streamNames))
                .subscribe();
        connection.set(disposable);
    }

    private Mono<JsonNode> parseJson(String text) {
        try {
            return Mono.just(objectMapper.readTree(text));
        } catch (Exception e) {
            log.warn("Failed to parse WS message: {}", e.getMessage());
            return Mono.empty();
        }
    }

    private void scheduleReconnect(Throwable err, String url, Set<String> streamNames) {
        startupState.markWsDisconnected();
        setWsStatus(Constants.WS_STATUS_DISCONNECTED);
        int attempt = backoffAttempt.incrementAndGet();
        var delay = computeBackoff(attempt);
        if (err != null) {
            log.warn("WS disconnected ({}), reconnecting in {} — attempt #{}", err.getMessage(), delay, attempt);
        } else {
            log.warn("WS terminated, reconnecting in {} — attempt #{}", delay, attempt);
        }
        Mono.delay(delay).subscribe(v -> doConnect(url, streamNames));
    }

    private Duration computeBackoff(int attempt) {
        var cfg = props.getWs().getReconnect();
        long base = Math.min(
                (long) (cfg.getInitialDelayMs() * Math.pow(cfg.getMultiplier(), attempt - 1)),
                cfg.getMaxDelayMs()
        );
        return Duration.ofMillis(base);
    }

    public Flux<JsonNode> combinedStream(Set<String> streamNames) {
        return inbound.asFlux()
                .filter(msg -> msg.has("stream"))
                .filter(msg -> streamNames.contains(msg.get("stream").asText()))
                .map(msg -> msg.get("data"));
    }

    // Returns (streamName, data) pairs — needed when symbol must be extracted from the stream name (e.g. depth events have no "s" field in payload)
    public Flux<Map.Entry<String, JsonNode>> combinedStreamWithName(Set<String> streamNames) {
        return inbound.asFlux()
                .filter(msg -> msg.has("stream"))
                .filter(msg -> streamNames.contains(msg.get("stream").asText()))
                .map(msg -> Map.entry(msg.get("stream").asText(), msg.get("data")));
    }

    private void setWsStatus(String status) {
        redis.opsForValue().set(Constants.REDIS_WS_STATUS_KEY, status).subscribe();
    }
}
