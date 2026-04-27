package com.haizz.exchange.wallet.application;

import com.haizz.exchange.wallet.domain.*;
import com.haizz.exchange.wallet.domain.exception.DepositAmountExceedsLimitException;
import com.haizz.exchange.wallet.domain.exception.DepositAssetNotSupportedException;
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
public class DepositUseCase {

    private static final BigDecimal MAX_DEPOSIT = new BigDecimal("100000");
    private static final String USDT = "USDT";
    private static final long IDEMPOTENCY_WINDOW_SECONDS = 60;

    private final WalletRepository walletRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final DepositRecordRepository depositRecordRepository;
    private final WalletEventPublisher eventPublisher;

    public record Result(DepositRecord depositRecord, Wallet wallet) {}

    @Transactional
    public Result execute(UUID userId, String assetCode, BigDecimal amount, String clientRequestId) {
        if (!USDT.equalsIgnoreCase(assetCode)) {
            throw new DepositAssetNotSupportedException();
        }

        if (amount.compareTo(MAX_DEPOSIT) > 0) {
            throw new DepositAmountExceedsLimitException(MAX_DEPOSIT);
        }

        // Idempotency check
        Instant since = Instant.now().minusSeconds(IDEMPOTENCY_WINDOW_SECONDS);
        var existing = depositRecordRepository.findByIdempotencyKey(userId, clientRequestId, since);
        if (existing.isPresent()) {
            DepositRecord record = existing.get();
            Wallet wallet = walletRepository.findByUserIdAndAssetCode(userId, USDT)
                    .orElseThrow(() -> new WalletNotFoundException(userId.toString(), USDT));
            log.info("Returning idempotent deposit result depositId={}", record.getDepositId());
            return new Result(record, wallet);
        }

        Wallet wallet = walletRepository.findByUserIdAndAssetCode(userId, USDT)
                .orElseThrow(() -> new WalletNotFoundException(userId.toString(), USDT));

        DepositRecord depositRecord = DepositRecord.pending(userId, USDT, amount, clientRequestId);
        depositRecordRepository.save(depositRecord);

        wallet.setAvailableBalance(wallet.getAvailableBalance().add(amount));
        wallet.setTotalBalance(wallet.getTotalBalance().add(amount));
        wallet.assertInvariant();

        WalletTransaction txn = WalletTransaction.of(
                wallet.getWalletId(), userId, USDT,
                WalletTxnType.DEPOSIT,
                amount, BigDecimal.ZERO,
                wallet.getAvailableBalance(), wallet.getFrozenBalance(),
                "DEPOSIT", depositRecord.getDepositId().toString());
        walletTransactionRepository.save(txn);

        depositRecord.confirm(txn.getTxnId());
        walletRepository.save(wallet);

        eventPublisher.enqueueWalletTransaction(txn);

        log.info("Deposit confirmed depositId={} userId={} amount={} USDT",
                depositRecord.getDepositId(), userId, amount);
        return new Result(depositRecord, wallet);
    }
}
