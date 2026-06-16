package com.haizz.exchange.matching.infrastructure.persistence;

import com.haizz.exchange.matching.domain.MatchingOutbox;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface MatchingOutboxRepository extends JpaRepository<MatchingOutbox, UUID> {

    @Query("SELECT o FROM MatchingOutbox o WHERE o.publishedAt IS NULL ORDER BY o.createdAt ASC")
    List<MatchingOutbox> findPendingEvents(Pageable pageable);
}
