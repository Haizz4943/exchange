package com.haizz.exchange.wallet.application;

import com.haizz.exchange.wallet.config.AppProperties;
import com.haizz.exchange.wallet.domain.Wallet;
import com.haizz.exchange.wallet.domain.WalletTransaction;
import com.haizz.exchange.wallet.domain.WalletTxnType;
import com.haizz.exchange.wallet.infrastructure.kafka.WalletEventPublisher;
import com.haizz.exchange.wallet.infrastructure.kafka.event.UserRegisteredEvent;
import com.haizz.exchange.wallet.infrastructure.persistence.WalletRepository;
import com.haizz.exchange.wallet.infrastructure.persistence.WalletTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class InitializeWalletsUseCase {

    private static final BigDecimal INITIAL_USDT_GRANT = new BigDecimal("10000");
    private static final String USDT = "USDT";

    private final WalletRepository walletRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final WalletEventPublisher eventPublisher;
    private final AppProperties appProperties;

    /**
     * Idempotent: safe to re-consume the same user.registered event.
     * Checks for existing USDT wallet before creating any wallets.
     */
    @Transactional
    public void execute(UserRegisteredEvent event) {
        UUID userId = event.userId();

        if (walletRepository.existsByUserIdAndAssetCode(userId, USDT)) {
            log.info("Wallets already exist for userId={} — skipping", userId);
            return;
        }

        List<String> assets = appProperties.assets().supportedAssets();

        for (String assetCode : assets) {
            Wallet wallet = Wallet.createZeroBalance(userId, assetCode);

            if (USDT.equals(assetCode)) {
                wallet.setAvailableBalance(INITIAL_USDT_GRANT);
                wallet.setTotalBalance(INITIAL_USDT_GRANT);
            }

            walletRepository.save(wallet);

            if (USDT.equals(assetCode)) {
                WalletTransaction grant = WalletTransaction.of(
                        wallet.getWalletId(), userId, USDT,
                        WalletTxnType.SIGNUP_GRANT,
                        INITIAL_USDT_GRANT, BigDecimal.ZERO,
                        INITIAL_USDT_GRANT, BigDecimal.ZERO,
                        "USER", userId.toString());
                walletTransactionRepository.save(grant);
                eventPublisher.enqueueWalletTransaction(grant);
            }
        }

        log.info("Initialized {} wallets for userId={}", assets.size(), userId);
    }
}
