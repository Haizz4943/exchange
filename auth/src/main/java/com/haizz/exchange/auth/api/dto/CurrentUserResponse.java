package com.haizz.exchange.auth.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.haizz.exchange.auth.domain.User;

import java.util.UUID;

public record CurrentUserResponse(
        @JsonProperty("user_id")           UUID userId,
        String email,
        @JsonProperty("external_provider") String externalProvider,
        String status
) {
    public static CurrentUserResponse from(User user) {
        return new CurrentUserResponse(
                user.getId(),
                user.getEmailNormalized(),
                user.getExternalProvider(),
                user.getStatus().name()
        );
    }
}
