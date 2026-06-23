package com.haizz.exchange.wallet.application;

import com.haizz.exchange.wallet.domain.Wallet;
import com.haizz.exchange.wallet.infrastructure.persistence.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GetWalletsUseCase {

    private final WalletRepository walletRepository;
    private final InitializeWalletsUseCase initializeWalletsUseCase;

    @Transactional(readOnly = true)
    public List<Wallet> execute(UUID userId) {
        // Lazy provisioning (SR-024): self-heal a user whose user.registered event was lost.
        initializeWalletsUseCase.provisionIfMissing(userId);
        return walletRepository.findByUserId(userId);
    }
}
