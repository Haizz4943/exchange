package com.haizz.exchange.wallet.application;

import com.haizz.exchange.wallet.domain.Wallet;
import com.haizz.exchange.wallet.domain.WalletTransaction;
import com.haizz.exchange.wallet.domain.WalletTxnType;
import com.haizz.exchange.wallet.domain.exception.FreezeConflictException;
import com.haizz.exchange.wallet.domain.exception.InsufficientAvailableBalanceException;
import com.haizz.exchange.wallet.domain.exception.WalletConcurrentModificationException;
import com.haizz.exchange.wallet.domain.exception.WalletNotFoundException;
import com.haizz.exchange.wallet.infrastructure.kafka.WalletEventPublisher;
import com.haizz.exchange.wallet.infrastructure.persistence.WalletRepository;
import com.haizz.exchange.wallet.infrastructure.persistence.WalletTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class FreezeUseCase {

    private static final int MAX_OPTIMISTIC_RETRIES = 3;

    private final WalletRepository walletRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final WalletEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final InitializeWalletsUseCase initializeWalletsUseCase;

    public record Result(Wallet wallet) {}

    public Result execute(UUID userId, String assetCode, BigDecimal amount, String referenceId) {
        // Lazy provisioning (SR-024): self-heal a user whose user.registered event was lost.
        // Done once, before the optimistic-retry loop (its own REQUIRES_NEW transaction).
        initializeWalletsUseCase.provisionIfMissing(userId);

        for (int attempt = 0; attempt <= MAX_OPTIMISTIC_RETRIES; attempt++) {
            final boolean usePessimistic = (attempt == MAX_OPTIMISTIC_RETRIES);
            try {
                return transactionTemplate.execute(status -> {
                    // Idempotency check
                    Optional<WalletTransaction> existingFreeze =
                            walletTransactionRepository.findByReferenceTypeAndReferenceIdAndType(
                                    "ORDER", referenceId, WalletTxnType.ORDER_FREEZE);

                    if (existingFreeze.isPresent()) {
                        WalletTransaction existing = existingFreeze.get();
                        BigDecimal frozenAmount = existing.getDeltaAvailable().negate();
                        if (frozenAmount.compareTo(amount) != 0) {
                            throw new FreezeConflictException(referenceId);
                        }
                        Wallet w = walletRepository.findByUserIdAndAssetCode(userId, assetCode)
                                .orElseThrow(() -> new WalletNotFoundException(userId.toString(), assetCode));
                        log.info("Returning idempotent freeze result referenceId={}", referenceId);
                        return new Result(w);
                    }

                    Wallet wallet = usePessimistic
                            ? walletRepository.findByUserIdAndAssetCodeForUpdate(userId, assetCode)
                                    .orElseThrow(() -> new WalletNotFoundException(userId.toString(), assetCode))
                            : walletRepository.findByUserIdAndAssetCode(userId, assetCode)
                                    .orElseThrow(() -> new WalletNotFoundException(userId.toString(), assetCode));

                    if (wallet.getAvailableBalance().compareTo(amount) < 0) {
                        throw new InsufficientAvailableBalanceException(
                                wallet.getAvailableBalance(), amount, wallet.getFrozenBalance());
                    }

                    wallet.setAvailableBalance(wallet.getAvailableBalance().subtract(amount));
                    wallet.setFrozenBalance(wallet.getFrozenBalance().add(amount));
                    wallet.assertInvariant();

                    WalletTransaction txn = WalletTransaction.of(
                            wallet.getWalletId(), userId, assetCode,
                            WalletTxnType.ORDER_FREEZE,
                            amount.negate(), amount,
                            wallet.getAvailableBalance(), wallet.getFrozenBalance(),
                            "ORDER", referenceId);
                    walletTransactionRepository.save(txn);
                    walletRepository.save(wallet);
                    eventPublisher.enqueueWalletTransaction(txn);

                    log.info("Frozen {} {} for orderId={} userId={}", amount, assetCode, referenceId, userId);
                    return new Result(wallet);
                });
            } catch (ObjectOptimisticLockingFailureException e) {
                if (attempt == MAX_OPTIMISTIC_RETRIES) {
                    throw new WalletConcurrentModificationException();
                }
                log.warn("Optimistic lock conflict on freeze userId={} attempt={}", userId, attempt + 1);
                sleepBackoff(attempt);
            }
        }
        throw new WalletConcurrentModificationException();
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(10L * (1L << attempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
