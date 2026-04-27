package com.haizz.exchange.auth.application;

import com.haizz.exchange.auth.domain.Credential;
import com.haizz.exchange.auth.domain.HashAlgorithm;
import com.haizz.exchange.auth.domain.User;
import com.haizz.exchange.auth.domain.exception.EmailAlreadyExistsException;
import com.haizz.exchange.auth.domain.exception.ValidationException;
import com.haizz.exchange.auth.infrastructure.kafka.UserEventPublisher;
import com.haizz.exchange.auth.infrastructure.persistence.CredentialRepository;
import com.haizz.exchange.auth.infrastructure.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RegisterUseCase {

    private static final int MIN_PASSWORD_LENGTH = 8;

    private final UserRepository userRepository;
    private final CredentialRepository credentialRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserEventPublisher userEventPublisher;

    @Transactional
    public User execute(String email, String password) {
        if (email == null || email.isBlank() || email.length() > 254 || !email.contains("@")) {
            throw new ValidationException("INVALID_EMAIL", "Invalid email address.");
        }

        validatePasswordStrength(password);

        String normalized = email.toLowerCase().trim();

        if (userRepository.existsByEmailNormalized(normalized)) {
            throw new EmailAlreadyExistsException();
        }

        User user = User.createLocal(email);
        String hash = passwordEncoder.encode(password);
        Credential credential = Credential.of(user.getId(), hash, HashAlgorithm.BCRYPT);

        try {
            userRepository.save(user);
            credentialRepository.save(credential);
            userEventPublisher.enqueueUserRegistered(user);
        } catch (DataIntegrityViolationException e) {
            // Race condition — concurrent registration with same email
            throw new EmailAlreadyExistsException();
        }

        log.info("User registered: userId={} email={}", user.getId(), normalized);
        return user;
    }

    private void validatePasswordStrength(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new ValidationException("PASSWORD_TOO_WEAK",
                    "Password must be at least 8 characters.");
        }
        boolean hasUpper = password.chars().anyMatch(Character::isUpperCase);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (!hasUpper || !hasDigit) {
            throw new ValidationException("PASSWORD_TOO_WEAK",
                    "Password must contain at least one uppercase letter and one digit.");
        }
    }
}
