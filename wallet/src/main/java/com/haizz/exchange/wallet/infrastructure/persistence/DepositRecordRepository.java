package com.haizz.exchange.wallet.infrastructure.persistence;

import com.haizz.exchange.wallet.domain.DepositRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface DepositRecordRepository extends JpaRepository<DepositRecord, UUID> {

    Page<DepositRecord> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    /** Idempotency: find recent deposit with same clientRequestId within window */
    @Query("SELECT d FROM DepositRecord d WHERE d.userId = :userId " +
           "AND d.clientRequestId = :clientRequestId " +
           "AND d.createdAt >= :since")
    Optional<DepositRecord> findByIdempotencyKey(
            @Param("userId") UUID userId,
            @Param("clientRequestId") String clientRequestId,
            @Param("since") Instant since);
}
