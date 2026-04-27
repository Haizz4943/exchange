package com.haizz.exchange.auth.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.haizz.exchange.auth.domain.User;

import java.time.Instant;
import java.util.UUID;

public record RegisterResponse(
        @JsonProperty("user_id") UUID userId,
        String email,
        @JsonProperty("created_at") Instant createdAt
) {
    public static RegisterResponse from(User user) {
        return new RegisterResponse(user.getId(), user.getEmailNormalized(), user.getCreatedAt());
    }
}
