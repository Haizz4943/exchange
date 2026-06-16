package com.haizz.exchange.matching.infrastructure.persistence;

import com.haizz.exchange.matching.domain.Trade;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TradeRepository extends JpaRepository<Trade, UUID> {

    Page<Trade> findByUserId(UUID userId, Pageable pageable);

    Page<Trade> findByUserIdOrderByExecutedAtDesc(UUID userId, Pageable pageable);

    List<Trade> findByOrderId(UUID orderId);
}
