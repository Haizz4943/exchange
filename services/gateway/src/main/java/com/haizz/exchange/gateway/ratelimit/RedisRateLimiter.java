package com.haizz.exchange.gateway.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Redis token-bucket rate limiter using Lua script for atomic check-and-decrement.
 *
 * Lua script implements token bucket:
 *   - KEYS[1]: Redis key for the bucket
 *   - ARGV[1]: capacity (max tokens / burst)
 *   - ARGV[2]: refill_rate (tokens/second)
 *   - ARGV[3]: current time in seconds (epoch)
 *   - ARGV[4]: TTL in seconds
 * Returns 1 if allowed, 0 if denied.
 */
@Slf4j
// Explicit bean name avoids collision with Spring Cloud Gateway's auto-configured
// "redisRateLimiter" bean (GatewayRedisAutoConfiguration) since the 2025.1 train.
@Component("haizzRedisRateLimiter")
@RequiredArgsConstructor
public class RedisRateLimiter {

    // Token bucket Lua script — atomic check-and-decrement per spec §5.2
    private static final String LUA_SCRIPT = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill_rate = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local ttl = tonumber(ARGV[4])

            local bucket = redis.call('HMGET', key, 'tokens', 'last_refill')
            local tokens = tonumber(bucket[1])
            local last_refill = tonumber(bucket[2])

            if tokens == nil then
                tokens = capacity
                last_refill = now
            end

            local elapsed = now - last_refill
            local refill = math.floor(elapsed * refill_rate)
            tokens = math.min(capacity, tokens + refill)
            last_refill = now

            if tokens < 1 then
                redis.call('HMSET', key, 'tokens', tokens, 'last_refill', last_refill)
                redis.call('EXPIRE', key, ttl)
                return 0
            end

            tokens = tokens - 1
            redis.call('HMSET', key, 'tokens', tokens, 'last_refill', last_refill)
            redis.call('EXPIRE', key, ttl)
            return 1
            """;

    private final ReactiveStringRedisTemplate redisTemplate;

    private final RedisScript<Long> rateLimitScript =
            RedisScript.of(LUA_SCRIPT, Long.class);

    /**
     * Check if the given key is within rate limits.
     *
     * @param key        Redis key (e.g. "gw:rl:user:uuid" or "gw:rl:ip:1.2.3.4")
     * @param capacity   Burst capacity (max tokens)
     * @param refillRate Tokens per second (sustained rate)
     * @return Mono<Boolean> true if allowed, false if rate limited
     */
    public Mono<Boolean> isAllowed(String key, int capacity, double refillRate) {
        long nowSeconds = System.currentTimeMillis() / 1000;
        long ttl = Math.max(60, (long) (capacity / refillRate) * 2);

        return redisTemplate.execute(
                        rateLimitScript,
                        List.of(key),
                        String.valueOf(capacity),
                        String.valueOf(refillRate),
                        String.valueOf(nowSeconds),
                        String.valueOf(ttl))
                .next()
                .map(result -> result != null && result == 1L)
                .onErrorReturn(true); // On Redis error, allow the request (fail open)
    }
}
