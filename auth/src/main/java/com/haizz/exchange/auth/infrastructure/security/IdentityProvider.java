package com.haizz.exchange.auth.infrastructure.security;

/**
 * Abstraction over identity sources (local email/password in Stage 1, SSO in Stage 2).
 * Login orchestration delegates to this interface — it never references bcrypt directly.
 */
public interface IdentityProvider {

    UserIdentity authenticate(String email, String password);
}
