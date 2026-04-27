package com.haizz.exchange.auth.api;

import com.haizz.exchange.auth.api.dto.ValidateTokenRequest;
import com.haizz.exchange.auth.api.dto.ValidateTokenResponse;
import com.haizz.exchange.auth.application.ValidateTokenUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/auth")
@RequiredArgsConstructor
public class InternalAuthController {

    private final ValidateTokenUseCase validateTokenUseCase;

    @PostMapping("/validate-token")
    public ResponseEntity<ValidateTokenResponse> validateToken(
            @Valid @RequestBody ValidateTokenRequest request) {

        ValidateTokenUseCase.ValidationResult result = validateTokenUseCase.execute(request.token());

        return ResponseEntity.ok(new ValidateTokenResponse(
                result.valid(),
                result.userId(),
                result.expiresAt(),
                result.reason()
        ));
    }
}
