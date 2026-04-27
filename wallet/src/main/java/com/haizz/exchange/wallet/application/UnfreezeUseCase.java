package com.haizz.exchange.wallet.application;

import com.haizz.exchange.wallet.domain.Wallet;
import com.haizz.exchange.wallet.domain.WalletTransaction;
import com.haizz.exchange.wallet.domain.WalletTxnType;
import com.haizz.exchange.wallet.domain.exception.InsufficientFrozenBalanceException;
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
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UnfreezeUseCase {

    private static final int MAX_OPTIMISTIC_RETRIES = 3;

    private final WalletRepository walletRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final WalletEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    public record Result(Wallet wallet) {}

    public Result execute(UUID userId, String assetCode, BigDecimal amount,
                          String referenceId, String reason) {
        for (int attempt = 0; attempt <= MAX_OPTIMISTIC_RETRIES; attempt++) {
            final boolean usePessimistic = (attempt == MAX_OPTIMISTIC_RETRIES);
            try {
                return transactionTemplate.execute(status -> {
                    // Idempotency: (referenceId + reason) is the composite key
                    String idempotencyKey = referenceId + ":" + reason;
                    var existing = walletTransactionRepository.findByReferenceTypeAndReferenceIdAndType(
                            "ORDER", idempotencyKey, WalletTxnType.ORDER_UNFREEZE);
                    if (existing.isPresent()) {
                        Wallet w = walletRepository.findByUserIdAndAssetCode(userId, assetCode)
                                .orElseThrow(() -> new WalletNotFoundException(userId.toString(), assetCode));
                        log.info("Returning idempotent unfreeze result referenceId={} reason={}",
                                referenceId, reason);
                        return new Result(w);
                    }

                    Wallet wallet = usePessimistic
                            ? walletRepository.findByUserIdAndAssetCodeForUpdate(userId, assetCode)
                                    .orElseThrow(() -> new WalletNotFoundException(userId.toString(), assetCode))
                            : walletRepository.findByUserIdAndAssetCode(userId, assetCode)
                                    .orElseThrow(() -> new WalletNotFoundException(userId.toString(), assetCode));

                    if (wallet.getFrozenBalance().compareTo(amount) < 0) {
                        throw new InsufficientFrozenBalanceException();
                    }

                    wallet.setFrozenBalance(wallet.getFrozenBalance().subtract(amount));
                    wallet.setAvailableBalance(wallet.getAvailableBalance().add(amount));
                    wallet.assertInvariant();

                    WalletTransaction txn = WalletTransaction.of(
                            wallet.getWalletId(), userId, assetCode,
                            WalletTxnType.ORDER_UNFREEZE,
                            amount, amount.negate(),
                            wallet.getAvailableBalance(), wallet.getFrozenBalance(),
                            "ORDER", idempotencyKey);
                    walletTransactionRepository.save(txn);
                    walletRepository.save(wallet);
                    eventPublisher.enqueueWalletTransaction(txn);

                    log.info("Unfrozen {} {} orderId={} reason={} userId={}",
                            amount, assetCode, referenceId, reason, userId);
                    return new Result(wallet);
                });
            } catch (ObjectOptimisticLockingFailureException e) {
                if (attempt == MAX_OPTIMISTIC_RETRIES) {
                    throw new WalletConcurrentModificationException();
                }
                log.warn("Optimistic lock conflict on unfreeze userId={} attempt={}", userId, attempt + 1);
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
