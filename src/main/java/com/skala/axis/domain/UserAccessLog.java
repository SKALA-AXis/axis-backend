/*
 * 작성일: 2026-05-21
 * 작성자: 박진
 * 변경이력:
 *   2026-05-21 박진 — 로그인/회원가입 기능으로 사용자 접근 로그 엔티티 추가
 */
package com.skala.axis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
@Table(name = "user_access_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAccessLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "action_type", nullable = false, length = 60)
    private String actionType;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "session_id")
    private UUID sessionId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> metadata;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    public static UserAccessLog create(
            User user,
            String actionType,
            boolean success,
            String ipAddress,
            String userAgent,
            UUID sessionId,
            Map<String, Object> metadata
    ) {
        UserAccessLog log = new UserAccessLog();
        log.user = user;
        log.actionType = actionType;
        log.success = success;
        log.ipAddress = ipAddress;
        log.userAgent = userAgent;
        log.sessionId = sessionId;
        log.metadata = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
        log.occurredAt = Instant.now();
        return log;
    }
}
