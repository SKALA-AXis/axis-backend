/*
 * 작성일: 2026-05-22
 * 작성자: 박진
 * 변경이력:
 *   2026-05-22 박진 — 비밀번호 찾기 업데이트에 따른 비밀번호 재설정 확인 응답 DTO 작성
 */
package com.skala.axis.dto.auth;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PasswordResetConfirmResponse(
        @JsonProperty("password_reset") boolean passwordReset
) {
}
