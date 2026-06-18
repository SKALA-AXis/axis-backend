/*
 * 작성일: 2026-05-29
 * 작성자: 안가은
 * 변경이력:
 *   2026-05-29 안가은 — 카드뉴스 소프트삭제와 관리자 감사로그 API 추가 시 도메인 생성
 */
package com.skala.axis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "admin_audit_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdminAuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "actor_email", nullable = false, length = 320)
    private String actorEmail;

    @Column(name = "action_type", nullable = false, length = 80)
    private String actionType;

    @Column(name = "resource_type", nullable = false, length = 40)
    private String resourceType;

    @Column(name = "resource_id", nullable = false, length = 80)
    private String resourceId;

    @Column(name = "reason", length = 1000)
    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static AdminAuditLog create(
            UUID actorUserId,
            String actorEmail,
            String actionType,
            String resourceType,
            String resourceId,
            String reason,
            Map<String, Object> payload
    ) {
        AdminAuditLog log = new AdminAuditLog();
        log.actorUserId = actorUserId;
        log.actorEmail = actorEmail == null || actorEmail.isBlank() ? "admin" : actorEmail;
        log.actionType = actionType;
        log.resourceType = resourceType;
        log.resourceId = resourceId;
        log.reason = reason == null || reason.isBlank() ? null : reason;
        log.payload = payload == null ? Map.of() : new LinkedHashMap<>(payload);
        log.createdAt = Instant.now();
        return log;
    }
}
