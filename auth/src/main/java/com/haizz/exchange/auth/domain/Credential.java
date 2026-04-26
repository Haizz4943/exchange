package com.haizz.exchange.auth.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "credentials")
@Getter
@Setter
@NoArgsConstructor
public class Credential {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "hash_algorithm", nullable = false, length = 20)
    private HashAlgorithm hashAlgorithm;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static Credential of(UUID userId, String passwordHash, HashAlgorithm algorithm) {
        Credential c = new Credential();
        c.userId = userId;
        c.passwordHash = passwordHash;
        c.hashAlgorithm = algorithm;
        c.updatedAt = Instant.now();
        return c;
    }
}
