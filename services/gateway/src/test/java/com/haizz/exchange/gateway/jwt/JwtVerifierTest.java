package com.haizz.exchange.gateway.jwt;

import com.haizz.exchange.gateway.config.GatewayProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for JwtVerifier — no infrastructure required (pure Java).
 */
class JwtVerifierTest {

    private static final String SECRET = "change-me-in-prod-must-be-at-least-32-chars!";
    private static final String ISSUER = "haizz-auth";

    private JwtVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        GatewayProperties.JwtProperties jwtProps = new GatewayProperties.JwtProperties(
                "HS256", SECRET, null, ISSUER);
        GatewayProperties props = new GatewayProperties(jwtProps, null, null, null);
        verifier = new JwtVerifier(props);
        verifier.init();
    }

    @Test
    void validToken_returnsClaims() throws Exception {
        String token = buildToken(UUID.randomUUID().toString(), "user@example.com",
                Instant.now().plusSeconds(3600));
        JwtClaims claims = verifier.verify(token);
        assertThat(claims.userId()).isNotBlank();
        assertThat(claims.email()).isEqualTo("user@example.com");
        assertThat(claims.scope()).isEqualTo("user");
    }

    @Test
    void expiredToken_throwsTokenExpired() throws Exception {
        String token = buildToken(UUID.randomUUID().toString(), "user@example.com",
                Instant.now().minusSeconds(10));
        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(JwtException.class)
                .hasFieldOrPropertyWithValue("code", "TOKEN_EXPIRED");
    }

    @Test
    void missingToken_throwsMissingToken() {
        assertThatThrownBy(() -> verifier.verify(null))
                .isInstanceOf(JwtException.class)
                .hasFieldOrPropertyWithValue("code", "MISSING_TOKEN");
    }

    @Test
    void malformedToken_throwsInvalidToken() {
        assertThatThrownBy(() -> verifier.verify("not.a.jwt"))
                .isInstanceOf(JwtException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_TOKEN");
    }

    @Test
    void wrongIssuer_throwsInvalidToken() throws Exception {
        String token = buildTokenWithIssuer(UUID.randomUUID().toString(), "user@example.com",
                "wrong-issuer", Instant.now().plusSeconds(3600));
        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(JwtException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_TOKEN");
    }

    @Test
    void wrongSecret_throwsInvalidToken() throws Exception {
        String token = buildTokenWithDifferentSecret(UUID.randomUUID().toString(), "user@example.com",
                Instant.now().plusSeconds(3600));
        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(JwtException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_TOKEN");
    }

    // --- helpers ---

    private String buildToken(String userId, String email, Instant exp) throws Exception {
        return buildTokenWithIssuer(userId, email, ISSUER, exp);
    }

    private String buildTokenWithIssuer(String userId, String email, String issuer, Instant exp) throws Exception {
        return buildTokenWithSecret(userId, email, issuer, SECRET, exp);
    }

    private String buildTokenWithDifferentSecret(String userId, String email, Instant exp) throws Exception {
        return buildTokenWithSecret(userId, email, ISSUER, "completely-different-secret-here-12345678", exp);
    }

    private String buildTokenWithSecret(String userId, String email, String issuer, String secret, Instant exp) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(userId)
                .claim("email", email)
                .claim("scope", "user")
                .claim("jti", UUID.randomUUID().toString())
                .expirationTime(Date.from(exp))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}
