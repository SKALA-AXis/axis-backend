package com.skala.axis.dto.auth;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record AuthResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("expires_at") Instant expiresAt,
        @JsonProperty("expires_in_seconds") long expiresInSeconds,
        UserProfileResponse user
) {
}
