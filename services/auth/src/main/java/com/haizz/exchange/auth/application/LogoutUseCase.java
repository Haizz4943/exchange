package com.haizz.exchange.auth.application;

import com.haizz.exchange.auth.infrastructure.jwt.JwtTokenProvider;
import com.haizz.exchange.auth.infrastructure.persistence.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class LogoutUseCase {

    private final SessionRepository sessionRepository;
    private final JwtTokenProvider jwtTokenProvider;

    @Transactional
    public void execute(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            // No-op: FE discards tokens locally; server has nothing to revoke
            return;
        }

        String tokenHash = jwtTokenProvider.hashRefreshToken(rawRefreshToken);
        sessionRepository.findByRefreshTokenHash(tokenHash).ifPresent(session -> {
            if (!session.isRevoked()) {
                session.revoke();
                sessionRepository.save(session);
                log.info("Session revoked on logout: sessionId={} userId={}", session.getId(), session.getUserId());
            }
        });
    }
}
