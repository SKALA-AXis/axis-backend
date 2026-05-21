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

    @Column(nullable = false, length = 300)
    private String title;

    @Column
    private String message;

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

    public static UserNotification create(
            User user,
            String notificationType,
            String title,
            String message,
            String targetView,
            String targetResourceId,
            Map<String, Object> payload
    ) {
        UserNotification notification = new UserNotification();
        notification.user = user;
        notification.notificationType = notificationType;
        notification.title = title;
        notification.message = message;
        notification.targetView = targetView;
        notification.targetResourceId = targetResourceId;
        notification.payload = payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
        notification.createdAt = Instant.now();
        return notification;
    }

    public void markRead() {
        if (readAt == null) {
            readAt = Instant.now();
        }
    }
}
