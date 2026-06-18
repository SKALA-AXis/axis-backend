/*
 * 작성일: 2026-05-21
 * 작성자: 박진
 * 변경이력:
 *   2026-05-21 박진 — 로그인/회원가입 기능 추가에 따른 인증 응답 DTO 작성
 */
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
