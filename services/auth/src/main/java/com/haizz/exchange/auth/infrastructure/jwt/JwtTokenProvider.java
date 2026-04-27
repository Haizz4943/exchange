package com.haizz.exchange.auth.infrastructure.jwt;

import com.haizz.exchange.auth.config.AppProperties;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    private final AppProperties appProperties;

    private JWSSigner signer;
    private JWSVerifier verifier;
    private JWSAlgorithm algorithm;

    @PostConstruct
    void init() throws Exception {
        AppProperties.JwtProperties jwt = appProperties.jwt();
        if ("RS256".equalsIgnoreCase(jwt.algorithm())) {
            RSAKey rsaKey = new RSAKeyGenerator(2048)
                    .keyID(UUID.randomUUID().toString())
                    .generate();
            signer = new RSASSASigner(rsaKey);
            verifier = new RSASSAVerifier(rsaKey.toRSAPublicKey());
            algorithm = JWSAlgorithm.RS256;
            log.info("JWT: using RS256 with generated key (set jwt.private-key-path for persistence)");
        } else {
            byte[] secret = jwt.secret().getBytes(StandardCharsets.UTF_8);
            signer = new MACSigner(secret);
            verifier = new MACVerifier(secret);
            algorithm = JWSAlgorithm.HS256;
            log.info("JWT: using HS256");
        }
    }

    public String generateAccessToken(UUID userId, String email) {
        AppProperties.JwtProperties jwt = appProperties.jwt();
        Instant now = Instant.now();

        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(jwt.issuer())
                    .subject(userId.toString())
                    .claim("email", email)
                    .claim("scope", "user")
                    .claim("jti", UUID.randomUUID().toString())
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(jwt.accessTokenTtlSeconds())))
                    .build();

            SignedJWT signedJWT = new SignedJWT(new JWSHeader(algorithm), claims);
            signedJWT.sign(signer);
            return signedJWT.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign JWT", e);
        }
    }

    public String generateRefreshToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String hashRefreshToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash refresh token", e);
        }
    }

    public JwtValidationResult validate(String token) {
        try {
            SignedJWT signedJWT = SignedJWT.parse(token);
            if (!signedJWT.verify(verifier)) {
                return JwtValidationResult.invalid("INVALID_SIGNATURE");
            }

            JWTClaimsSet claims = signedJWT.getJWTClaimsSet();
            if (claims.getExpirationTime().before(new Date())) {
                return JwtValidationResult.invalid("EXPIRED");
            }

            UUID userId = UUID.fromString(claims.getSubject());
            String email = (String) claims.getClaim("email");
            return JwtValidationResult.valid(userId, email, claims.getExpirationTime().toInstant());
        } catch (Exception e) {
            return JwtValidationResult.invalid("INVALID_TOKEN");
        }
    }

    public record JwtValidationResult(
            boolean valid,
            UUID userId,
            String email,
            Instant expiresAt,
            String reason
    ) {
        static JwtValidationResult valid(UUID userId, String email, Instant expiresAt) {
            return new JwtValidationResult(true, userId, email, expiresAt, null);
        }

        static JwtValidationResult invalid(String reason) {
            return new JwtValidationResult(false, null, null, null, reason);
        }
    }
}
