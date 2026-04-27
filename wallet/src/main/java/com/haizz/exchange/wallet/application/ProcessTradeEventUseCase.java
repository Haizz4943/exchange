package com.haizz.exchange.wallet.application;

import com.haizz.exchange.wallet.domain.Wallet;
import com.haizz.exchange.wallet.domain.WalletTransaction;
import com.haizz.exchange.wallet.domain.WalletTxnType;
import com.haizz.exchange.wallet.domain.exception.WalletNotFoundException;
import com.haizz.exchange.wallet.infrastructure.kafka.WalletEventPublisher;
import com.haizz.exchange.wallet.infrastructure.kafka.event.TradeExecutedEvent;
import com.haizz.exchange.wallet.infrastructure.persistence.WalletRepository;
import com.haizz.exchange.wallet.infrastructure.persistence.WalletTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Applies trade fills to wallet balances.
 *
 * Lock ordering: wallets are always loaded alphabetical by assetCode to prevent deadlocks
 * when multi-user trade events are added in a future version.
 *
 * BUY:  debit quoteQuantity from quote frozen → credit (quantity - fee) to base available
 * SELL: debit quantity from base frozen       → credit (quoteQuantity - fee) to quote available
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessTradeEventUseCase {

    private final WalletRepository walletRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final WalletEventPublisher eventPublisher;

    @Transactional
    public void execute(TradeExecutedEvent event) {
        String tradeId = event.tradeId().toString();

        // Idempotency: if any txn with this tradeId already exists, skip
        if (walletTransactionRepository.existsByReferenceTypeAndReferenceId("TRADE", tradeId)) {
            log.info("Trade {} already processed — skipping", tradeId);
            return;
        }

        UUID userId = event.userId();
        boolean isBuy = "BUY".equalsIgnoreCase(event.side());

        String debitAsset  = isBuy ? event.quoteAsset() : event.baseAsset();
        String creditAsset = isBuy ? event.baseAsset()  : event.quoteAsset();

        BigDecimal debitAmount  = event.quoteQuantity(); // always the quote side for buy, base side for sell
        if (!isBuy) {
            debitAmount = event.quantity(); // sell: debit base quantity
        }

        BigDecimal creditAmount = isBuy
                ? event.quantity().subtract(event.feeAmount())   // buy receives base minus fee
                : event.quoteQuantity().subtract(event.feeAmount()); // sell receives quote minus fee

        // Load wallets in alphabetical order to prevent future deadlocks
        List<String> sortedAssets = List.of(debitAsset, creditAsset).stream().sorted().toList();

        Wallet firstWallet  = loadWallet(userId, sortedAssets.get(0));
        Wallet secondWallet = loadWallet(userId, sortedAssets.get(1));

        Wallet debitWallet  = debitAsset.equals(sortedAssets.get(0))  ? firstWallet  : secondWallet;
        Wallet creditWallet = creditAsset.equals(sortedAssets.get(0)) ? firstWallet  : secondWallet;

        // --- Debit wallet (consume from frozen) ---
        if (debitWallet.getFrozenBalance().compareTo(debitAmount) < 0) {
            log.error("Insufficient frozen balance on trade debit: tradeId={} walletId={} frozen={} debit={}",
                    tradeId, debitWallet.getWalletId(), debitWallet.getFrozenBalance(), debitAmount);
            // Still process to avoid leaving system in inconsistent state; log for manual reconciliation
            debitAmount = debitWallet.getFrozenBalance();
        }

        debitWallet.setFrozenBalance(debitWallet.getFrozenBalance().subtract(debitAmount));
        debitWallet.setTotalBalance(debitWallet.getTotalBalance().subtract(debitAmount));
        debitWallet.assertInvariant();

        WalletTransaction debitTxn = WalletTransaction.of(
                debitWallet.getWalletId(), userId, debitAsset,
                WalletTxnType.TRADE_DEBIT,
                BigDecimal.ZERO, debitAmount.negate(),
                debitWallet.getAvailableBalance(), debitWallet.getFrozenBalance(),
                "TRADE", tradeId);
        walletTransactionRepository.save(debitTxn);
        eventPublisher.enqueueWalletTransaction(debitTxn);

        // --- Residual freeze release (isFinalFill with leftover buffer) ---
        if (event.isFinalFill()
                && event.residualFrozenAmount() != null
                && event.residualFrozenAmount().compareTo(BigDecimal.ZERO) > 0
                && event.residualAsset() != null) {

            String residualAsset = event.residualAsset();
            BigDecimal residual = event.residualFrozenAmount();

            Wallet residualWallet = residualAsset.equals(debitAsset) ? debitWallet : creditWallet;

            if (residualWallet.getFrozenBalance().compareTo(residual) >= 0) {
                residualWallet.setFrozenBalance(residualWallet.getFrozenBalance().subtract(residual));
                residualWallet.setAvailableBalance(residualWallet.getAvailableBalance().add(residual));
                residualWallet.assertInvariant();

                WalletTransaction unfreezeTxn = WalletTransaction.of(
                        residualWallet.getWalletId(), userId, residualAsset,
                        WalletTxnType.ORDER_UNFREEZE,
                        residual, residual.negate(),
                        residualWallet.getAvailableBalance(), residualWallet.getFrozenBalance(),
                        "ORDER", event.orderId().toString() + ":FILL_LEFTOVER_RELEASE");
                walletTransactionRepository.save(unfreezeTxn);
                eventPublisher.enqueueWalletTransaction(unfreezeTxn);

                log.info("Released residual freeze orderId={} amount={} {}",
                        event.orderId(), residual, residualAsset);
            }
        }

        // --- Credit wallet (add to available, net of fee) ---
        creditWallet.setAvailableBalance(creditWallet.getAvailableBalance().add(creditAmount));
        creditWallet.setTotalBalance(creditWallet.getTotalBalance().add(creditAmount));
        creditWallet.assertInvariant();

        WalletTransaction creditTxn = WalletTransaction.of(
                creditWallet.getWalletId(), userId, creditAsset,
                WalletTxnType.TRADE_CREDIT,
                creditAmount, BigDecimal.ZERO,
                creditWallet.getAvailableBalance(), creditWallet.getFrozenBalance(),
                "TRADE", tradeId);
        walletTransactionRepository.save(creditTxn);
        eventPublisher.enqueueWalletTransaction(creditTxn);

        // --- Fee audit record (zero-delta, informational) ---
        WalletTransaction feeTxn = WalletTransaction.fee(
                creditWallet.getWalletId(), userId, creditAsset,
                creditWallet.getAvailableBalance(), creditWallet.getFrozenBalance(),
                tradeId, event.feeAmount(), event.feeAsset());
        walletTransactionRepository.save(feeTxn);

        walletRepository.save(debitWallet);
        walletRepository.save(creditWallet);

        log.info("Trade processed tradeId={} userId={} side={} debit={} {} credit={} {}",
                tradeId, userId, event.side(),
                debitAmount, debitAsset, creditAmount, creditAsset);
    }

    private Wallet loadWallet(UUID userId, String assetCode) {
        return walletRepository.findByUserIdAndAssetCodeForUpdate(userId, assetCode)
                .orElseThrow(() -> new WalletNotFoundException(userId.toString(), assetCode));
    }
}
