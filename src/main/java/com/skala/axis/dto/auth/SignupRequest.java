package com.skala.axis.dto.auth;

public record SignupRequest(
        String email,
        String password,
        String name,
        String department,
        String role
) {
}
