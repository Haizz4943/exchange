package com.haizz.exchange.auth.infrastructure.security;

import com.haizz.exchange.auth.domain.Credential;
import com.haizz.exchange.auth.domain.User;
import com.haizz.exchange.auth.domain.exception.AccountDisabledException;
import com.haizz.exchange.auth.domain.exception.InvalidCredentialsException;
import com.haizz.exchange.auth.infrastructure.persistence.CredentialRepository;
import com.haizz.exchange.auth.infrastructure.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class LocalIdentityProvider implements IdentityProvider {

    private static final String DUMMY_HASH =
            "$2b$12$dummyhashfortimingequalityyyyyyyyyyyyyyyyyyyyyyyy";

    private final UserRepository userRepository;
    private final CredentialRepository credentialRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public UserIdentity authenticate(String email, String password) {
        String normalized = email.toLowerCase().trim();
        Optional<User> userOpt = userRepository.findByEmailNormalized(normalized);

        // Always run password check to prevent timing-based user enumeration
        if (userOpt.isEmpty() || !userOpt.get().isLocal()) {
            passwordEncoder.matches(password, DUMMY_HASH);
            throw new InvalidCredentialsException();
        }

        User user = userOpt.get();

        if (!user.isActive()) {
            throw new AccountDisabledException();
        }

        Credential credential = credentialRepository.findById(user.getId())
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(password, credential.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        return new UserIdentity(user.getId(), user.getEmailNormalized(), user.getExternalProvider());
    }
}
