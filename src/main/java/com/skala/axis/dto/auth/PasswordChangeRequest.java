/*
 * 작성일: 2026-05-21
 * 작성자: 박진
 * 변경이력:
 *   2026-05-21 박진 — 로그인/회원가입 기능 추가에 따른 비밀번호 변경 요청 DTO 작성
 */
package com.skala.axis.dto.auth;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PasswordChangeRequest(
        @JsonProperty("current_password") String currentPassword,
        @JsonProperty("new_password") String newPassword
) {
}
