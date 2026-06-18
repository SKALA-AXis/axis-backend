/*
 * 작성일: 2026-05-29
 * 작성자: 안가은
 * 변경이력:
 *   2026-05-29 안가은 — 카드뉴스 소프트삭제와 관리자 감사로그 API 추가 시 감사로그 리포지토리 작성
 */
package com.skala.axis.repository;

import com.skala.axis.domain.AdminAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, Long> {
    List<AdminAuditLog> findTop100ByOrderByCreatedAtDesc();

    List<AdminAuditLog> findByResourceTypeAndResourceIdInOrderByCreatedAtDesc(
            String resourceType,
            Collection<String> resourceIds
    );
}
