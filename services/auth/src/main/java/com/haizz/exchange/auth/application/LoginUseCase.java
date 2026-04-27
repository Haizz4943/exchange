package com.haizz.exchange.auth.application;

import com.haizz.exchange.auth.domain.LoginAttempt;
import com.haizz.exchange.auth.domain.Session;
import com.haizz.exchange.auth.domain.exception.AccountDisabledException;
import com.haizz.exchange.auth.domain.exception.InvalidCredentialsException;
import com.haizz.exchange.auth.domain.exception.RateLimitExceededException;
import com.haizz.exchange.auth.infrastructure.jwt.JwtTokenProvider;
import com.haizz.exchange.auth.infrastructure.persistence.LoginAttemptRepository;
import com.haizz.exchange.auth.infrastructure.persistence.SessionRepository;
import com.haizz.exchange.auth.infrastructure.redis.RateLimiterService;
import com.haizz.exchange.auth.infrastructure.security.IdentityProvider;
import com.haizz.exchange.auth.infrastructure.security.UserIdentity;
import com.haizz.exchange.auth.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoginUseCase {

    private final IdentityProvider identityProvider;
    private final JwtTokenProvider jwtTokenProvider;
    private final SessionRepository sessionRepository;
    private final LoginAttemptRepository loginAttemptRepository;
    private final RateLimiterService rateLimiterService;
    private final AppProperties appProperties;

    public record LoginResult(String accessToken, String refreshToken, long expiresIn) {}

    @Transactional
    public LoginResult execute(String email, String password, String userAgent, String ipAddress) {
        String normalized = email == null ? "" : email.toLowerCase().trim();

        try {
            rateLimiterService.checkLoginAllowed(normalized, ipAddress);
        } catch (RateLimitExceededException e) {
            loginAttemptRepository.save(LoginAttempt.of(normalized, ipAddress, false));
            throw e;
        }

        UserIdentity identity;
        try {
            identity = identityProvider.authenticate(email, password);
        } catch (AccountDisabledException e) {
            loginAttemptRepository.save(LoginAttempt.of(normalized, ipAddress, false));
            throw e;
        } catch (InvalidCredentialsException e) {
            loginAttemptRepository.save(LoginAttempt.of(normalized, ipAddress, false));
            rateLimiterService.recordFailedLogin(normalized);
            throw e;
        }

        // Successful authentication — clear fail counter
        rateLimiterService.clearFailedLogin(normalized);

        String accessToken = jwtTokenProvider.generateAccessToken(identity.userId(), identity.email());
        String rawRefresh = jwtTokenProvider.generateRefreshToken();
        String tokenHash = jwtTokenProvider.hashRefreshToken(rawRefresh);

        long ttl = appProperties.jwt().refreshTokenTtlSeconds();
        Session session = Session.create(identity.userId(), tokenHash, ttl, userAgent, ipAddress);
        sessionRepository.save(session);

        loginAttemptRepository.save(LoginAttempt.of(normalized, ipAddress, true));

        log.info("Login success: userId={}", identity.userId());
        return new LoginResult(accessToken, rawRefresh, appProperties.jwt().accessTokenTtlSeconds());
    }
}
