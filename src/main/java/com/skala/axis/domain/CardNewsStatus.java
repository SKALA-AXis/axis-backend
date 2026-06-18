/*
 * 작성일: 2026-05-29
 * 작성자: 안가은
 * 변경이력:
 *   2026-05-29 안가은 — 카드뉴스 소프트삭제와 관리자 감사로그 API 추가 시 상태 enum 생성
 */
package com.skala.axis.domain;

public enum CardNewsStatus {
    ACTIVE,
    PENDING,
    DELETED
}
