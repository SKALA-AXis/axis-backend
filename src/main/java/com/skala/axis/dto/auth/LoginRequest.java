package com.skala.axis.dto.auth;

import com.fasterxml.jackson.annotation.JsonProperty;

public record LoginRequest(
        String email,
        String password,
        @JsonProperty("remember_me") Boolean rememberMe
) {
}
