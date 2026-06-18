/*
 * 작성일: 2026-05-22
 * 작성자: 박진
 * 변경이력:
 *   2026-05-22 박진 — 비밀번호 찾기 응답 DTO 추가
 */
package com.skala.axis.dto.auth;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PasswordResetRequestResponse(
        boolean accepted,
        String message,
        @JsonProperty("expires_in_minutes") long expiresInMinutes
) {
}
