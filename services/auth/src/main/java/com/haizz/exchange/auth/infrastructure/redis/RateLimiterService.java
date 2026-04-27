package com.haizz.exchange.auth.infrastructure.redis;

import com.haizz.exchange.auth.config.AppProperties;
import com.haizz.exchange.auth.domain.exception.RateLimitExceededException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimiterService {

    private static final String FAIL_KEY_PREFIX    = "auth:login_fails:";
    private static final String LOCKOUT_KEY_PREFIX = "auth:lockout:";
    private static final String IP_KEY_PREFIX      = "auth:login_attempts_ip:";

    private final StringRedisTemplate redisTemplate;
    private final AppProperties appProperties;

    public void checkLoginAllowed(String emailNormalized, String ipAddress) {
        AppProperties.RateLimitProperties rl = appProperties.rateLimit();

        if (Boolean.TRUE.equals(redisTemplate.hasKey(LOCKOUT_KEY_PREFIX + emailNormalized))) {
            log.warn("Login blocked — email lockout active: {}", emailNormalized);
            throw new RateLimitExceededException();
        }

        Long ipCount = redisTemplate.opsForValue().increment(IP_KEY_PREFIX + ipAddress);
        if (ipCount != null && ipCount == 1) {
            redisTemplate.expire(IP_KEY_PREFIX + ipAddress, Duration.ofSeconds(rl.ipWindowSeconds()));
        }
        if (ipCount != null && ipCount > rl.maxIpAttempts()) {
            log.warn("Login blocked — IP rate limit exceeded: {}", ipAddress);
            throw new RateLimitExceededException();
        }
    }

    public void recordFailedLogin(String emailNormalized) {
        AppProperties.RateLimitProperties rl = appProperties.rateLimit();
        String failKey = FAIL_KEY_PREFIX + emailNormalized;

        Long count = redisTemplate.opsForValue().increment(failKey);
        if (count != null && count == 1) {
            redisTemplate.expire(failKey, Duration.ofSeconds(rl.failWindowSeconds()));
        }

        if (count != null && count >= rl.maxFailAttempts()) {
            String lockKey = LOCKOUT_KEY_PREFIX + emailNormalized;
            redisTemplate.opsForValue().set(lockKey, "1", Duration.ofSeconds(rl.lockoutSeconds()));
            log.warn("Login lockout activated for email: {}", emailNormalized);
        }
    }

    public void clearFailedLogin(String emailNormalized) {
        redisTemplate.delete(FAIL_KEY_PREFIX + emailNormalized);
        redisTemplate.delete(LOCKOUT_KEY_PREFIX + emailNormalized);
    }
}
