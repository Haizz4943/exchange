package com.haizz.exchange.auth.config;

import com.haizz.exchange.auth.infrastructure.jwt.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Configuration
@RequiredArgsConstructor
public class JwtConfig {

    private final AppProperties appProperties;

    /**
     * JwtDecoder used by Spring Security resource-server to validate tokens
     * on secured endpoints (GET /auth/me, POST /auth/logout).
     * Must use the same key/algorithm as JwtTokenProvider.
     */
    @Bean
    JwtDecoder jwtDecoder(JwtTokenProvider provider) {
        AppProperties.JwtProperties jwt = appProperties.jwt();

        if ("RS256".equalsIgnoreCase(jwt.algorithm())) {
            // For RS256, the public key is available inside JwtTokenProvider after @PostConstruct.
            // Use the token provider's validate method — delegate via a custom JwtDecoder.
            return token -> {
                JwtTokenProvider.JwtValidationResult result = provider.validate(token);
                if (!result.valid()) {
                    throw new org.springframework.security.oauth2.jwt.BadJwtException(
                            result.reason() != null ? result.reason() : "Invalid JWT");
                }
                return org.springframework.security.oauth2.jwt.Jwt.withTokenValue(token)
                        .header("alg", "RS256")
                        .claim("sub", result.userId().toString())
                        .claim("email", result.email())
                        .expiresAt(result.expiresAt())
                        .issuedAt(result.expiresAt().minusSeconds(appProperties.jwt().accessTokenTtlSeconds()))
                        .build();
            };
        }

        // HS256
        SecretKey key = new SecretKeySpec(
                jwt.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).build();
    }
}
