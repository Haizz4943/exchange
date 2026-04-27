package com.haizz.exchange.wallet.infrastructure.persistence;

import com.haizz.exchange.wallet.domain.WithdrawalRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface WithdrawalRecordRepository extends JpaRepository<WithdrawalRecord, UUID> {

    Page<WithdrawalRecord> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    @Query("SELECT w FROM WithdrawalRecord w WHERE w.userId = :userId " +
           "AND w.clientRequestId = :clientRequestId " +
           "AND w.createdAt >= :since")
    Optional<WithdrawalRecord> findByIdempotencyKey(
            @Param("userId") UUID userId,
            @Param("clientRequestId") String clientRequestId,
            @Param("since") Instant since);
}
