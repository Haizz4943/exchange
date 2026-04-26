package com.haizz.exchange.auth.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record RefreshRequest(
        @NotBlank @JsonProperty("refresh_token") String refreshToken
) {
}
