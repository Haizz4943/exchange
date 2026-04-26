package com.haizz.exchange.auth.infrastructure.security;

import java.util.UUID;

public record UserIdentity(UUID userId, String email, String externalProvider) {
}
