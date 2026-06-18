/*
 * 작성일: 2026-05-29
 * 작성자: 안가은
 * 변경이력:
 *   2026-05-29 안가은 — 카드뉴스 소프트삭제 및 관리자 감사로그 API용 상태변경 요청 DTO 추가
 */
package com.skala.axis.dto.admin;

public record AdminCardNewsStatusUpdateRequest(
        String status,
        String reason
) {
}
