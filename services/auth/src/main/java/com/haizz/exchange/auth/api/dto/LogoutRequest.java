package com.haizz.exchange.auth.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record LogoutRequest(
        @JsonProperty("refresh_token") String refreshToken
) {
}
