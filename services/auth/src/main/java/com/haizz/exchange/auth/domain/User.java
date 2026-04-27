package com.haizz.exchange.auth.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User {

    @Id
    private UUID id;

    @Column(name = "email", nullable = false, length = 254)
    private String email;

    @Column(name = "email_normalized", nullable = false, unique = true, length = 254)
    private String emailNormalized;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "external_provider", nullable = false, length = 40)
    private String externalProvider = "local";

    @Column(name = "external_subject_id", length = 128)
    private String externalSubjectId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static User createLocal(String email) {
        User user = new User();
        user.id = UUID.randomUUID();
        user.email = email;
        user.emailNormalized = email.toLowerCase().trim();
        user.externalProvider = "local";
        user.status = UserStatus.ACTIVE;
        user.createdAt = Instant.now();
        user.updatedAt = user.createdAt;
        return user;
    }

    public boolean isLocal() {
        return "local".equals(externalProvider);
    }

    public boolean isActive() {
        return UserStatus.ACTIVE == status;
    }

    @PreUpdate
    private void onUpdate() {
        updatedAt = Instant.now();
    }
}
