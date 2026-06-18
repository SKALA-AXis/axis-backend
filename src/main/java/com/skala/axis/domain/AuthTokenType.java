/*
 * 작성일: 2026-05-21
 * 작성자: 박진
 * 변경이력:
 *   2026-05-21 박진 — 로그인/회원가입 추가 시 인증 토큰 타입 enum 생성, 비밀번호 찾기 업데이트
 */
package com.skala.axis.domain;

public enum AuthTokenType {
    EMAIL_VERIFICATION,
    PASSWORD_RESET,
    REFRESH
}
