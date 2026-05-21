package com.skala.axis.dto.auth;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record SignupResponse(
        UserProfileResponse user,
        @JsonProperty("email_verification_required") boolean emailVerificationRequired,
        @JsonProperty("verification_expires_at") Instant verificationExpiresAt
) {
}
