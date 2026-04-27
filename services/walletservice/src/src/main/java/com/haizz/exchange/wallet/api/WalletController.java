package com.haizz.exchange.wallet.api;

import com.haizz.exchange.wallet.api.dto.*;
import com.haizz.exchange.wallet.application.*;
import com.haizz.exchange.wallet.domain.WalletTxnType;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class WalletController {

    private final GetWalletsUseCase getWalletsUseCase;
    private final GetTransactionsUseCase getTransactionsUseCase;
    private final DepositUseCase depositUseCase;
    private final WithdrawUseCase withdrawUseCase;

    @GetMapping("/wallets/me")
    public ResponseEntity<WalletsResponse> getMyWallets(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = userId(jwt);
        List<WalletDto> wallets = getWalletsUseCase.execute(userId).stream()
                .map(WalletDto::from)
                .toList();
        return ResponseEntity.ok(new WalletsResponse(wallets, null));
    }

    @GetMapping("/wallet-transactions")
    public ResponseEntity<Page<WalletTransactionDto>> getTransactions(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String assetCode,
            @RequestParam(required = false) WalletTxnType type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        size = Math.min(size, 200);
        PageRequest pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<WalletTransactionDto> result = getTransactionsUseCase
                .execute(userId(jwt), assetCode, type, from, to, pageable)
                .map(WalletTransactionDto::from);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/deposits")
    public ResponseEntity<DepositResponse> deposit(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody DepositRequest request) {

        DepositUseCase.Result result = depositUseCase.execute(
                userId(jwt), request.assetCode(), request.amount(), request.clientRequestId());
        return ResponseEntity.ok(DepositResponse.from(result.depositRecord(), result.wallet()));
    }

    @GetMapping("/deposits")
    public ResponseEntity<Page<DepositRecordDto>> getDeposits(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        // Simplified: re-use transaction history filtered by DEPOSIT type
        PageRequest pageable = PageRequest.of(page, Math.min(size, 200),
                Sort.by("createdAt").descending());
        Page<WalletTransactionDto> txns = getTransactionsUseCase
                .execute(userId(jwt), null, WalletTxnType.DEPOSIT, null, null, pageable)
                .map(WalletTransactionDto::from);
        return ResponseEntity.ok(txns.map(t -> new DepositRecordDto(
                t.referenceId(), t.deltaTotal(), t.assetCode(), t.createdAt())));
    }

    @PostMapping("/withdrawals")
    public ResponseEntity<WithdrawResponse> withdraw(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody WithdrawRequest request) {

        WithdrawUseCase.Result result = withdrawUseCase.execute(
                userId(jwt), request.assetCode(), request.amount(), request.clientRequestId());
        return ResponseEntity.ok(WithdrawResponse.from(result.withdrawalRecord(), result.wallet()));
    }

    @GetMapping("/withdrawals")
    public ResponseEntity<Page<DepositRecordDto>> getWithdrawals(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        PageRequest pageable = PageRequest.of(page, Math.min(size, 200),
                Sort.by("createdAt").descending());
        Page<WalletTransactionDto> txns = getTransactionsUseCase
                .execute(userId(jwt), null, WalletTxnType.WITHDRAWAL, null, null, pageable)
                .map(WalletTransactionDto::from);
        return ResponseEntity.ok(txns.map(t -> new DepositRecordDto(
                t.referenceId(), t.deltaTotal().abs(), t.assetCode(), t.createdAt())));
    }

    private UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
