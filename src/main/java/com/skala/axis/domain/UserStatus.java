/*
 * 작성일: 2026-05-21
 * 작성자: 박진
 * 변경이력:
 *   2026-05-21 박진 — 로그인/회원가입 기능 추가 시 사용자 상태 enum 정의
 */
package com.skala.axis.domain;

public enum UserStatus {
    PENDING,
    ACTIVE,
    SUSPENDED,
    WITHDRAWN
}
