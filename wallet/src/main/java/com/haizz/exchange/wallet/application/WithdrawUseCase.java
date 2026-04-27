package com.haizz.exchange.wallet.application;

import com.haizz.exchange.wallet.domain.*;
import com.haizz.exchange.wallet.domain.exception.InsufficientAvailableBalanceException;
import com.haizz.exchange.wallet.domain.exception.WalletNotFoundException;
import com.haizz.exchange.wallet.infrastructure.kafka.WalletEventPublisher;
import com.haizz.exchange.wallet.infrastructure.persistence.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawUseCase {

    private static final long IDEMPOTENCY_WINDOW_SECONDS = 60;

    private final WalletRepository walletRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final WithdrawalRecordRepository withdrawalRecordRepository;
    private final WalletEventPublisher eventPublisher;

    public record Result(WithdrawalRecord withdrawalRecord, Wallet wallet) {}

    @Transactional
    public Result execute(UUID userId, String assetCode, BigDecimal amount, String clientRequestId) {
        // Idempotency check
        Instant since = Instant.now().minusSeconds(IDEMPOTENCY_WINDOW_SECONDS);
        var existing = withdrawalRecordRepository.findByIdempotencyKey(userId, clientRequestId, since);
        if (existing.isPresent()) {
            WithdrawalRecord record = existing.get();
            Wallet wallet = walletRepository.findByUserIdAndAssetCode(userId, assetCode)
                    .orElseThrow(() -> new WalletNotFoundException(userId.toString(), assetCode));
            log.info("Returning idempotent withdrawal result withdrawalId={}",
                    record.getWithdrawalId());
            return new Result(record, wallet);
        }

        Wallet wallet = walletRepository.findByUserIdAndAssetCode(userId, assetCode)
                .orElseThrow(() -> new WalletNotFoundException(userId.toString(), assetCode));

        if (wallet.getAvailableBalance().compareTo(amount) < 0) {
            throw new InsufficientAvailableBalanceException(
                    wallet.getAvailableBalance(), amount, wallet.getFrozenBalance());
        }

        WithdrawalRecord withdrawalRecord = WithdrawalRecord.pending(
                userId, assetCode, amount, clientRequestId);
        withdrawalRecordRepository.save(withdrawalRecord);

        wallet.setAvailableBalance(wallet.getAvailableBalance().subtract(amount));
        wallet.setTotalBalance(wallet.getTotalBalance().subtract(amount));
        wallet.assertInvariant();

        WalletTransaction txn = WalletTransaction.of(
                wallet.getWalletId(), userId, assetCode,
                WalletTxnType.WITHDRAWAL,
                amount.negate(), BigDecimal.ZERO,
                wallet.getAvailableBalance(), wallet.getFrozenBalance(),
                "WITHDRAWAL", withdrawalRecord.getWithdrawalId().toString());
        walletTransactionRepository.save(txn);

        withdrawalRecord.confirm(txn.getTxnId());
        walletRepository.save(wallet);

        eventPublisher.enqueueWalletTransaction(txn);

        log.info("Withdrawal confirmed withdrawalId={} userId={} amount={} {}",
                withdrawalRecord.getWithdrawalId(), userId, amount, assetCode);
        return new Result(withdrawalRecord, wallet);
    }
}
