package com.haizz.exchange.auth.infrastructure.persistence;

import com.haizz.exchange.auth.domain.Session;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionRepository extends JpaRepository<Session, UUID> {

    Optional<Session> findByRefreshTokenHash(String refreshTokenHash);

    List<Session> findAllByUserIdAndRevokedAtIsNull(UUID userId);

    @Modifying
    @Query("UPDATE Session s SET s.revokedAt = :revokedAt WHERE s.userId = :userId AND s.revokedAt IS NULL")
    int revokeAllActiveSessionsForUser(UUID userId, Instant revokedAt);
}
