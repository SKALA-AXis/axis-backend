/*
 * 작성일: 2026-05-21
 * 작성자: 박진
 * 변경이력:
 *   2026-05-21 박진 — 로그인/회원가입 추가 작업의 일부로 회원가입 응답 DTO 추가
 */
package com.skala.axis.dto.auth;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record SignupResponse(
        UserProfileResponse user,
        @JsonProperty("email_verification_required") boolean emailVerificationRequired,
        @JsonProperty("verification_expires_at") Instant verificationExpiresAt
) {
}
