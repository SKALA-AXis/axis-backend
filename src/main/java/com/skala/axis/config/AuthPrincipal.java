/*
 * 작성일: 2026-05-21
 * 작성자: 박진
 * 변경이력:
 *   2026-05-21 박진 — 로그인/회원가입 기능 추가
 */
package com.skala.axis.config;

import com.skala.axis.domain.UserRole;

import java.util.UUID;

public record AuthPrincipal(UUID userId, String email, UserRole role) {
}
