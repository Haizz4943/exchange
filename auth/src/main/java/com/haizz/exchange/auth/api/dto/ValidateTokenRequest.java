package com.haizz.exchange.auth.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ValidateTokenRequest(@NotBlank String token) {
}
