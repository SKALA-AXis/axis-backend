/*
 * 작성일: 2026-05-21
 * 작성자: 박진
 * 변경이력:
 *   2026-05-21 박진 — 로그인/회원가입 추가 작업의 일부로 사용자 프로필 응답 DTO 추가
 */
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
