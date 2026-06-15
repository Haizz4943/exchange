package com.haizz.exchange.order.infrastructure.persistence;

import com.haizz.exchange.order.domain.TradingPair;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TradingPairRepository extends JpaRepository<TradingPair, String> {

    Optional<TradingPair> findBySymbolAndEnabledTrue(String symbol);
}
