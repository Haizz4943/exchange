package com.haizz.exchange.order.infrastructure.persistence;

import com.haizz.exchange.order.domain.OrderOutbox;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface OrderOutboxRepository extends JpaRepository<OrderOutbox, UUID> {

    @Query("SELECT o FROM OrderOutbox o WHERE o.publishedAt IS NULL ORDER BY o.createdAt ASC")
    List<OrderOutbox> findPendingEvents(Pageable pageable);
}
