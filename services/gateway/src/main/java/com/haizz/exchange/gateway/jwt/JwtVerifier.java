package com.haizz.exchange.gateway.jwt;

import com.haizz.exchange.gateway.config.GatewayProperties;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Date;

/**
 * Verifies JWTs signed by the Auth Service.
 *
 * Dev mode: HS256 with the shared JWT_SECRET (same as auth service default).
 * Prod path (deferred): RS256 with PEM public key from gateway.jwt.public-key.
 *
 * DECISION: HS256 only in dev because auth service generates an ephemeral RSA key
 * per startup with no JWKS endpoint — RS256 local verification is impossible in dev.
 * See DECISIONS.md §1.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtVerifier {

    private final GatewayProperties properties;

    private JWSVerifier verifier;
    private String issuer;

    @PostConstruct
    void init() throws Exception {
        GatewayProperties.JwtProperties jwt = properties.jwt();
        issuer = jwt.issuer();

        if ("RS256".equalsIgnoreCase(jwt.algorithm()) && jwt.publicKey() != null && !jwt.publicKey().isBlank()) {
            // Parse PEM-encoded RSA public key (deferred prod path)
            String pem = jwt.publicKey()
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] keyBytes = Base64.getDecoder().decode(pem);
            RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(keyBytes));
            verifier = new RSASSAVerifier(publicKey);
            log.info("JWT verifier: RS256 with configured public key");
        } else {
            // HS256 — shared secret (dev default)
            byte[] secret = jwt.secret().getBytes(StandardCharsets.UTF_8);
            verifier = new MACVerifier(secret);
            log.info("JWT verifier: HS256 (dev mode)");
        }
    }

    /**
     * Verify token and return claims.
     * @throws JwtException with code MISSING_TOKEN / TOKEN_EXPIRED / INVALID_TOKEN
     */
    public JwtClaims verify(String token) {
        if (token == null || token.isBlank()) {
            throw new JwtException("MISSING_TOKEN", "Token is missing");
        }
        try {
            SignedJWT jwt = SignedJWT.parse(token);

            if (!jwt.verify(verifier)) {
                throw new JwtException("INVALID_TOKEN", "Invalid token signature");
            }

            var claims = jwt.getJWTClaimsSet();

            if (claims.getExpirationTime() == null || claims.getExpirationTime().before(new Date())) {
                throw new JwtException("TOKEN_EXPIRED", "Token has expired");
            }

            if (!issuer.equals(claims.getIssuer())) {
                throw new JwtException("INVALID_TOKEN", "Invalid token issuer");
            }

            return new JwtClaims(
                    claims.getSubject(),
                    (String) claims.getClaim("email"),
                    (String) claims.getClaim("scope"),
                    (String) claims.getClaim("jti"),
                    claims.getExpirationTime().toInstant()
            );
        } catch (JwtException e) {
            throw e;
        } catch (Exception e) {
            throw new JwtException("INVALID_TOKEN", "Malformed or unverifiable token: " + e.getMessage());
        }
    }
}
