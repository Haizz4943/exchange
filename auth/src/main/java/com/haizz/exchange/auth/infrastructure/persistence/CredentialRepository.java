package com.haizz.exchange.auth.infrastructure.persistence;

import com.haizz.exchange.auth.domain.Credential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CredentialRepository extends JpaRepository<Credential, UUID> {
}
