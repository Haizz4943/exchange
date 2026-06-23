package com.haizz.exchange.wallet.api;

import com.haizz.exchange.wallet.api.dto.BalanceQueryResponse;
import com.haizz.exchange.wallet.api.dto.FreezeRequest;
import com.haizz.exchange.wallet.api.dto.UnfreezeRequest;
import com.haizz.exchange.wallet.api.dto.WalletDto;
import com.haizz.exchange.wallet.application.FreezeUseCase;
import com.haizz.exchange.wallet.application.InitializeWalletsUseCase;
import com.haizz.exchange.wallet.application.UnfreezeUseCase;
import com.haizz.exchange.wallet.domain.exception.WalletNotFoundException;
import com.haizz.exchange.wallet.infrastructure.persistence.WalletRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Internal-only endpoints — not routed via the public API Gateway.
 * Network-trust model: accessible only within the Docker compose network.
 */
@RestController
@RequestMapping("/api/v1/wallets/internal")
@RequiredArgsConstructor
public class InternalWalletController {

    private final FreezeUseCase freezeUseCase;
    private final UnfreezeUseCase unfreezeUseCase;
    private final WalletRepository walletRepository;
    private final InitializeWalletsUseCase initializeWalletsUseCase;

    @PostMapping("/freeze")
    public ResponseEntity<WalletDto> freeze(@Valid @RequestBody FreezeRequest request) {
        FreezeUseCase.Result result = freezeUseCase.execute(
                request.userId(), request.assetCode(), request.amount(), request.referenceId());
        return ResponseEntity.ok(WalletDto.from(result.wallet()));
    }

    @PostMapping("/unfreeze")
    public ResponseEntity<WalletDto> unfreeze(@Valid @RequestBody UnfreezeRequest request) {
        UnfreezeUseCase.Result result = unfreezeUseCase.execute(
                request.userId(), request.assetCode(), request.amount(),
                request.referenceId(), request.reason());
        return ResponseEntity.ok(WalletDto.from(result.wallet()));
    }

    @GetMapping("/balance")
    public ResponseEntity<BalanceQueryResponse> getBalance(
            @RequestParam UUID userId,
            @RequestParam String assetCode) {
        // Lazy provisioning (SR-024): self-heal a user whose user.registered event was lost.
        initializeWalletsUseCase.provisionIfMissing(userId);
        return walletRepository.findByUserIdAndAssetCode(userId, assetCode)
                .map(w -> ResponseEntity.ok(BalanceQueryResponse.from(w)))
                .orElseThrow(() -> new WalletNotFoundException(userId.toString(), assetCode));
    }
}
