package com.skala.axis.dto.auth;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PasswordResetConfirmRequest(
        String token,
        @JsonProperty("new_password") String newPassword
) {
}
