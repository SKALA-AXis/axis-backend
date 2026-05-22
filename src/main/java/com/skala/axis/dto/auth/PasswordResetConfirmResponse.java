package com.skala.axis.dto.auth;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PasswordResetConfirmResponse(
        @JsonProperty("password_reset") boolean passwordReset
) {
}
