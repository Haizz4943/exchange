package com.haizz.exchange.gateway.jwt;

import java.time.Instant;

/**
 * Claims extracted from a verified JWT.
 * Claims match what auth service JwtTokenProvider generates:
 * sub(userId), email, scope("user"), jti — there is NO roles claim.
 */
public record JwtClaims(
        String userId,   // sub
        String email,
        String scope,    // "user" — used as X-User-Roles downstream
        String jti,
        Instant expiresAt
) {}
