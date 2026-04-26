package com.haizz.exchange.auth.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sessions")
@Getter
@Setter
@NoArgsConstructor
public class Session {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "refresh_token_hash", nullable = false, length = 255)
    private String refreshTokenHash;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    public static Session create(UUID userId, String refreshTokenHash, long ttlSeconds,
                                  String userAgent, String ipAddress) {
        Session s = new Session();
        s.id = UUID.randomUUID();
        s.userId = userId;
        s.refreshTokenHash = refreshTokenHash;
        s.issuedAt = Instant.now();
        s.expiresAt = s.issuedAt.plusSeconds(ttlSeconds);
        s.userAgent = userAgent;
        s.ipAddress = ipAddress;
        return s;
    }

    public boolean isValid() {
        return revokedAt == null && Instant.now().isBefore(expiresAt);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public void revoke() {
        this.revokedAt = Instant.now();
    }

    public void touch() {
        this.lastUsedAt = Instant.now();
    }
}
