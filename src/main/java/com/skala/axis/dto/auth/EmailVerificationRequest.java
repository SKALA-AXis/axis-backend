/*
 * 작성일: 2026-05-21
 * 작성자: 박진
 * 변경이력:
 *   2026-05-21 박진 — 로그인/회원가입 기능 추가에 따른 이메일 인증 요청 DTO 작성
 */
package com.skala.axis.dto.auth;

public record EmailVerificationRequest(String token, String email) {
}
