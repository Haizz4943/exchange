package com.haizz.exchange.auth.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ValidateTokenResponse(
        boolean valid,
        @JsonProperty("user_id")    String userId,
        @JsonProperty("expires_at") Instant expiresAt,
        String reason
) {
}
