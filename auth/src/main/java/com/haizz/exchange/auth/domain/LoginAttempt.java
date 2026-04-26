package com.haizz.exchange.auth.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "login_attempts")
@Getter
@NoArgsConstructor
public class LoginAttempt {

    @Id
    private UUID id;

    @Column(name = "email", length = 254)
    private String email;

    @Column(name = "ip_address", nullable = false, length = 45)
    private String ipAddress;

    @Column(name = "success", nullable = false)
    private boolean success;

    @Column(name = "attempted_at", nullable = false, updatable = false)
    private Instant attemptedAt;

    public static LoginAttempt of(String email, String ipAddress, boolean success) {
        LoginAttempt a = new LoginAttempt();
        a.id = UUID.randomUUID();
        a.email = email;
        a.ipAddress = ipAddress;
        a.success = success;
        a.attemptedAt = Instant.now();
        return a;
    }
}
