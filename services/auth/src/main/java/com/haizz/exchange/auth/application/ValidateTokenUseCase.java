package com.haizz.exchange.auth.application;

import com.haizz.exchange.auth.domain.User;
import com.haizz.exchange.auth.domain.UserStatus;
import com.haizz.exchange.auth.infrastructure.jwt.JwtTokenProvider;
import com.haizz.exchange.auth.infrastructure.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ValidateTokenUseCase {

    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;

    public record ValidationResult(boolean valid, String userId, Instant expiresAt, String reason) {}

    @Transactional(readOnly = true)
    public ValidationResult execute(String token) {
        JwtTokenProvider.JwtValidationResult result = jwtTokenProvider.validate(token);

        if (!result.valid()) {
            return new ValidationResult(false, null, null, result.reason());
        }

        Optional<User> userOpt = userRepository.findById(result.userId());
        if (userOpt.isEmpty() || userOpt.get().getStatus() == UserStatus.DISABLED) {
            return new ValidationResult(false, null, null, "USER_DISABLED");
        }

        return new ValidationResult(true, result.userId().toString(), result.expiresAt(), null);
    }
}
