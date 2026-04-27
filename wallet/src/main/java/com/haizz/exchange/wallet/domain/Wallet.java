package com.haizz.exchange.wallet.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wallets",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_wallets_user_asset",
                columnNames = {"user_id", "asset_code"}))
@Getter
@Setter
@NoArgsConstructor
public class Wallet {

    @Id
    @Column(name = "wallet_id")
    private UUID walletId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "asset_code", nullable = false, length = 10)
    private String assetCode;

    @Column(name = "total_balance", nullable = false, precision = 36, scale = 18)
    private BigDecimal totalBalance;

    @Column(name = "available_balance", nullable = false, precision = 36, scale = 18)
    private BigDecimal availableBalance;

    @Column(name = "frozen_balance", nullable = false, precision = 36, scale = 18)
    private BigDecimal frozenBalance;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static Wallet createZeroBalance(UUID userId, String assetCode) {
        Wallet w = new Wallet();
        w.walletId = UUID.randomUUID();
        w.userId = userId;
        w.assetCode = assetCode;
        w.totalBalance = BigDecimal.ZERO;
        w.availableBalance = BigDecimal.ZERO;
        w.frozenBalance = BigDecimal.ZERO;
        w.createdAt = Instant.now();
        w.updatedAt = w.createdAt;
        return w;
    }

    /** Validates invariant: total = available + frozen. Throws if violated. */
    public void assertInvariant() {
        BigDecimal sum = availableBalance.add(frozenBalance);
        if (totalBalance.compareTo(sum) != 0) {
            throw new IllegalStateException(
                    "Wallet invariant violated for walletId=" + walletId
                    + ": total=" + totalBalance + " != available=" + availableBalance
                    + " + frozen=" + frozenBalance);
        }
        if (availableBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalStateException("availableBalance < 0 for walletId=" + walletId);
        }
        if (frozenBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalStateException("frozenBalance < 0 for walletId=" + walletId);
        }
    }

    @PreUpdate
    private void onUpdate() {
        updatedAt = Instant.now();
    }
}
