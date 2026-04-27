package com.haizz.exchange.wallet.infrastructure.persistence;

import com.haizz.exchange.wallet.domain.WalletTransaction;
import com.haizz.exchange.wallet.domain.WalletTxnType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, UUID> {

    Page<WalletTransaction> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    @Query("SELECT t FROM WalletTransaction t WHERE t.userId = :userId " +
           "AND (:assetCode IS NULL OR t.assetCode = :assetCode) " +
           "AND (:type IS NULL OR t.type = :type) " +
           "AND (:from IS NULL OR t.createdAt >= :from) " +
           "AND (:to IS NULL OR t.createdAt <= :to) " +
           "ORDER BY t.createdAt DESC")
    Page<WalletTransaction> findByFilters(
            @Param("userId") UUID userId,
            @Param("assetCode") String assetCode,
            @Param("type") WalletTxnType type,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);

    /** Idempotency check: find existing txn by referenceType + referenceId + type */
    Optional<WalletTransaction> findByReferenceTypeAndReferenceIdAndType(
            String referenceType, String referenceId, WalletTxnType type);

    /** Check if a trade has already been processed (any txn with TRADE referenceId) */
    boolean existsByReferenceTypeAndReferenceId(String referenceType, String referenceId);
}
