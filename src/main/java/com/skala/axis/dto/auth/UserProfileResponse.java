package com.skala.axis.dto.auth;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

public record UserProfileResponse(
        UUID id,
        String email,
        String name,
        String department,
        String role,
        String status,
        @JsonProperty("email_verified") boolean emailVerified,
        @JsonProperty("last_login_at") Instant lastLoginAt
) {
}
