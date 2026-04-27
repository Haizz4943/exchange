package com.haizz.exchange.auth.infrastructure.persistence;

import com.haizz.exchange.auth.domain.AuthOutbox;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface AuthOutboxRepository extends JpaRepository<AuthOutbox, UUID> {

    @Query("SELECT o FROM AuthOutbox o WHERE o.publishedAt IS NULL ORDER BY o.createdAt ASC")
    List<AuthOutbox> findPendingEvents(Pageable pageable);
}
