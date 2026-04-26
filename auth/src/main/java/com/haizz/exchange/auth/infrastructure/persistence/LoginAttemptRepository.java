package com.haizz.exchange.auth.infrastructure.persistence;

import com.haizz.exchange.auth.domain.LoginAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface LoginAttemptRepository extends JpaRepository<LoginAttempt, UUID> {
}
