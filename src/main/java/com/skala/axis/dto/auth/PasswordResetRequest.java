/*
 * 작성일: 2026-05-22
 * 작성자: 박진
 * 변경이력:
 *   2026-05-22 박진 — 비밀번호 찾기 비밀번호 재설정 요청 DTO 추가
 */
package com.skala.axis.dto.auth;

public record PasswordResetRequest(String email) {
}
