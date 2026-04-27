package com.haizz.exchange.wallet.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "deposit_records")
@Getter
@Setter
@NoArgsConstructor
public class DepositRecord {

    @Id
    @Column(name = "deposit_id")
    private UUID depositId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "asset_code", nullable = false, length = 10)
    private String assetCode;

    @Column(name = "amount", nullable = false, precision = 36, scale = 18)
    private BigDecimal amount;

    @Column(name = "client_request_id", nullable = false, length = 64)
    private String clientRequestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DepositStatus status;

    @Column(name = "wallet_txn_id")
    private UUID walletTxnId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    public static DepositRecord pending(UUID userId, String assetCode,
                                        BigDecimal amount, String clientRequestId) {
        DepositRecord r = new DepositRecord();
        r.depositId = UUID.randomUUID();
        r.userId = userId;
        r.assetCode = assetCode;
        r.amount = amount;
        r.clientRequestId = clientRequestId;
        r.status = DepositStatus.PENDING;
        r.createdAt = Instant.now();
        return r;
    }

    public void confirm(UUID txnId) {
        this.status = DepositStatus.CONFIRMED;
        this.walletTxnId = txnId;
        this.confirmedAt = Instant.now();
    }
}
