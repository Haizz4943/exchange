package com.haizz.exchange.auth.application;

import com.haizz.exchange.auth.config.AppProperties;
import com.haizz.exchange.auth.domain.Session;
import com.haizz.exchange.auth.domain.User;
import com.haizz.exchange.auth.domain.exception.InvalidRefreshTokenException;
import com.haizz.exchange.auth.infrastructure.jwt.JwtTokenProvider;
import com.haizz.exchange.auth.infrastructure.persistence.SessionRepository;
import com.haizz.exchange.auth.infrastructure.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenUseCase {

    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final AppProperties appProperties;

    public record RefreshResult(String accessToken, String refreshToken, long expiresIn) {}

    @Transactional
    public RefreshResult execute(String rawRefreshToken, String userAgent, String ipAddress) {
        String tokenHash = jwtTokenProvider.hashRefreshToken(rawRefreshToken);

        Session session = sessionRepository.findByRefreshTokenHash(tokenHash)
                .orElseThrow(() -> new InvalidRefreshTokenException("INVALID_REFRESH_TOKEN"));

        // Reuse detection: if token was already revoked, someone is reusing it — revoke all sessions
        if (session.isRevoked()) {
            log.warn("SECURITY: Revoked refresh token reuse detected for userId={}. Revoking all sessions.",
                    session.getUserId());
            sessionRepository.revokeAllActiveSessionsForUser(session.getUserId(), Instant.now());
            throw new InvalidRefreshTokenException("INVALID_REFRESH_TOKEN");
        }

        if (session.isExpired()) {
            throw new InvalidRefreshTokenException("REFRESH_TOKEN_EXPIRED");
        }

        User user = userRepository.findById(session.getUserId())
                .orElseThrow(() -> new InvalidRefreshTokenException("INVALID_REFRESH_TOKEN"));

        // Rotate: revoke old session, issue new one
        session.revoke();
        sessionRepository.save(session);

        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmailNormalized());
        String newRawRefresh = jwtTokenProvider.generateRefreshToken();
        String newHash = jwtTokenProvider.hashRefreshToken(newRawRefresh);

        long ttl = appProperties.jwt().refreshTokenTtlSeconds();
        Session newSession = Session.create(user.getId(), newHash, ttl, userAgent, ipAddress);
        sessionRepository.save(newSession);

        log.info("Token refreshed: userId={}", user.getId());
        return new RefreshResult(accessToken, newRawRefresh, appProperties.jwt().accessTokenTtlSeconds());
    }
}
