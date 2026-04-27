package com.haizz.exchange.wallet.infrastructure.persistence;

import com.haizz.exchange.wallet.domain.Wallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    List<Wallet> findByUserId(UUID userId);

    Optional<Wallet> findByUserIdAndAssetCode(UUID userId, String assetCode);

    boolean existsByUserIdAndAssetCode(UUID userId, String assetCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Wallet w WHERE w.userId = :userId AND w.assetCode = :assetCode")
    Optional<Wallet> findByUserIdAndAssetCodeForUpdate(
            @Param("userId") UUID userId,
            @Param("assetCode") String assetCode);
}
