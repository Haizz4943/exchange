package com.haizz.exchange.wallet.application;

import com.haizz.exchange.wallet.domain.WalletTransaction;
import com.haizz.exchange.wallet.domain.WalletTxnType;
import com.haizz.exchange.wallet.infrastructure.persistence.WalletTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GetTransactionsUseCase {

    private final WalletTransactionRepository walletTransactionRepository;

    @Transactional(readOnly = true)
    public Page<WalletTransaction> execute(UUID userId, String assetCode, WalletTxnType type,
                                            Instant from, Instant to, Pageable pageable) {
        return walletTransactionRepository.findByFilters(userId, assetCode, type, from, to, pageable);
    }
}
