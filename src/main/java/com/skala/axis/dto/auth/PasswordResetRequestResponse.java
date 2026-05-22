package com.skala.axis.dto.auth;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PasswordResetRequestResponse(
        boolean accepted,
        String message,
        @JsonProperty("expires_in_minutes") long expiresInMinutes
) {
}
