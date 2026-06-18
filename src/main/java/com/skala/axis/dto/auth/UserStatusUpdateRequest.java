/*
 * 작성일: 2026-05-21
 * 작성자: 박진
 * 변경이력:
 *   2026-05-21 박진 — 로그인/회원가입 추가 작업의 일부로 사용자 상태 변경 요청 DTO 추가
 */
package com.skala.axis.dto.auth;

public record UserStatusUpdateRequest(String status) {
}
