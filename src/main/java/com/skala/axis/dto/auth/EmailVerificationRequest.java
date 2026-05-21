package com.skala.axis.dto.auth;

public record EmailVerificationRequest(String token, String email) {
}
