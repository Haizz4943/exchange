package com.haizz.exchange.order.infrastructure.persistence;

import com.haizz.exchange.order.domain.Order;
import com.haizz.exchange.order.domain.OrderState;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID>,
        JpaSpecificationExecutor<Order> {

    Optional<Order> findByIdAndUserId(UUID id, UUID userId);

    Page<Order> findByUserId(UUID userId, Pageable pageable);

    long countByUserIdAndPairAndStateIn(UUID userId, String pair, Collection<OrderState> states);

    Optional<Order> findByUserIdAndClientOrderId(UUID userId, UUID clientOrderId);

    /**
     * Internal open-orders projection for the Matching Engine startup index rebuild
     * (API_SPEC §3.7). Returns ALL users' orders in the given states ordered by
     * {@code createdAt ASC} (FIFO — matters for matching priority), paged.
     */
    Page<Order> findByStateInOrderByCreatedAtAsc(Collection<OrderState> states, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") UUID id);
}
