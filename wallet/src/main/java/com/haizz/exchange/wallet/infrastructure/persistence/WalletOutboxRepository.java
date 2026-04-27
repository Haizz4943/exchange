package com.haizz.exchange.wallet.infrastructure.persistence;

import com.haizz.exchange.wallet.domain.WalletOutbox;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface WalletOutboxRepository extends JpaRepository<WalletOutbox, UUID> {

    @Query("SELECT o FROM WalletOutbox o WHERE o.publishedAt IS NULL ORDER BY o.createdAt ASC")
    List<WalletOutbox> findPendingEvents(Pageable pageable);
}
