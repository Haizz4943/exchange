package com.haizz.exchange.wallet.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable audit record — no UPDATE or DELETE allowed at the repository layer.
 * Three signed delta columns model every operation type uniformly.
 */
@Entity
@Table(name = "wallet_transactions")
@Getter
@NoArgsConstructor
public class WalletTransaction {

    @Id
    @Column(name = "txn_id")
    private UUID txnId;

    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "asset_code", nullable = false, length = 10)
    private String assetCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private WalletTxnType type;

    @Column(name = "delta_available", nullable = false, precision = 36, scale = 18)
    private BigDecimal deltaAvailable;

    @Column(name = "delta_frozen", nullable = false, precision = 36, scale = 18)
    private BigDecimal deltaFrozen;

    @Column(name = "delta_total", nullable = false, precision = 36, scale = 18)
    private BigDecimal deltaTotal;

    @Column(name = "balance_after_available", nullable = false, precision = 36, scale = 18)
    private BigDecimal balanceAfterAvailable;

    @Column(name = "balance_after_frozen", nullable = false, precision = 36, scale = 18)
    private BigDecimal balanceAfterFrozen;

    @Column(name = "balance_after_total", nullable = false, precision = 36, scale = 18)
    private BigDecimal balanceAfterTotal;

    @Column(name = "reference_type", nullable = false, length = 20)
    private String referenceType;

    /** orderId, tradeId, depositId, withdrawalId, or userId */
    @Column(name = "reference_id", nullable = false, length = 64)
    private String referenceId;

    /** For FEE records — fee amount and asset in "amount:asset" format (e.g., "0.001:BTC"). */
    @Column(name = "metadata", length = 255)
    private String metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static WalletTransaction of(
            UUID walletId, UUID userId, String assetCode, WalletTxnType type,
            BigDecimal deltaAvailable, BigDecimal deltaFrozen,
            BigDecimal balanceAfterAvailable, BigDecimal balanceAfterFrozen,
            String referenceType, String referenceId) {

        WalletTransaction t = new WalletTransaction();
        t.txnId = UUID.randomUUID();
        t.walletId = walletId;
        t.userId = userId;
        t.assetCode = assetCode;
        t.type = type;
        t.deltaAvailable = deltaAvailable;
        t.deltaFrozen = deltaFrozen;
        t.deltaTotal = deltaAvailable.add(deltaFrozen);
        t.balanceAfterAvailable = balanceAfterAvailable;
        t.balanceAfterFrozen = balanceAfterFrozen;
        t.balanceAfterTotal = balanceAfterAvailable.add(balanceAfterFrozen);
        t.referenceType = referenceType;
        t.referenceId = referenceId;
        t.createdAt = Instant.now();
        return t;
    }

    public static WalletTransaction fee(
            UUID walletId, UUID userId, String assetCode,
            BigDecimal balanceAfterAvailable, BigDecimal balanceAfterFrozen,
            String referenceId, BigDecimal feeAmount, String feeAsset) {

        WalletTransaction t = of(walletId, userId, assetCode, WalletTxnType.FEE,
                BigDecimal.ZERO, BigDecimal.ZERO,
                balanceAfterAvailable, balanceAfterFrozen,
                "TRADE", referenceId);
        t.metadata = feeAmount.toPlainString() + ":" + feeAsset;
        return t;
    }
}
