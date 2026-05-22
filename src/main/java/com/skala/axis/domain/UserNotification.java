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
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "user_notifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserNotification {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "notification_type", nullable = false, length = 50)
    private String notificationType;

    @Column(nullable = false, length = 20)
    private String severity;

    @Column(nullable = false, length = 300)
    private String title;

    @Column
    private String message;

    @Column(name = "source_type", nullable = false, length = 50)
    private String sourceType;

    @Column(name = "source_id", length = 100)
    private String sourceId;

    @Column(name = "source_url", length = 500)
    private String sourceUrl;

    @Column(name = "company_name", length = 100)
    private String companyName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "matched_keywords", columnDefinition = "jsonb", nullable = false)
    private List<String> matchedKeywords;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> metadata;

    @Column(name = "dedupe_key", nullable = false, length = 200)
    private String dedupeKey;

    @Column(name = "target_view", length = 50)
    private String targetView;

    @Column(name = "target_resource_id", length = 100)
    private String targetResourceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> payload;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public static UserNotification create(
            User user,
            String notificationType,
            String title,
            String message,
            String targetView,
            String targetResourceId,
            Map<String, Object> payload
    ) {
        return create(
                user,
                notificationType,
                "NORMAL",
                title,
                message,
                "IN_APP",
                targetResourceId,
                null,
                null,
                List.of(),
                payload,
                "legacy:" + UUID.randomUUID(),
                targetView,
                targetResourceId,
                payload
        );
    }

    public static UserNotification create(
            User user,
            String notificationType,
            String severity,
            String title,
            String message,
            String sourceType,
            String sourceId,
            String sourceUrl,
            String companyName,
            List<String> matchedKeywords,
            Map<String, Object> metadata,
            String dedupeKey,
            String targetView,
            String targetResourceId,
            Map<String, Object> payload
    ) {
        Instant now = Instant.now();
        UserNotification notification = new UserNotification();
        notification.user = user;
        notification.notificationType = notificationType;
        notification.severity = severity == null || severity.isBlank() ? "NORMAL" : severity;
        notification.title = title;
        notification.message = message;
        notification.sourceType = sourceType == null || sourceType.isBlank() ? "IN_APP" : sourceType;
        notification.sourceId = sourceId;
        notification.sourceUrl = sourceUrl;
        notification.companyName = companyName;
        notification.matchedKeywords = matchedKeywords == null ? List.of() : List.copyOf(matchedKeywords);
        notification.metadata = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
        notification.dedupeKey = dedupeKey == null || dedupeKey.isBlank() ? "generated:" + UUID.randomUUID() : dedupeKey;
        notification.targetView = targetView;
        notification.targetResourceId = targetResourceId;
        notification.payload = payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
        notification.createdAt = now;
        notification.updatedAt = now;
        return notification;
    }

    public void markRead() {
        if (readAt == null) {
            readAt = Instant.now();
            touch();
        }
    }

    public void markDeleted() {
        if (deletedAt == null) {
            deletedAt = Instant.now();
            touch();
        }
    }

    private void touch() {
        updatedAt = Instant.now();
    }
}
