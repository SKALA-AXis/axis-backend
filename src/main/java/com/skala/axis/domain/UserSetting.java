/*
 * 작성일: 2026-05-21
 * 작성자: 박진
 * 변경이력:
 *   2026-05-21 박진 — 로그인/회원가입 기능 추가 시 사용자 설정 엔티티 정의
 */
package com.skala.axis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
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
@Table(name = "user_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserSetting {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "display_name", length = 120)
    private String displayName;

    @Column(length = 120)
    private String department;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "alert_rules", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> alertRules;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "notification_settings", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> notificationSettings;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "view_preferences", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> viewPreferences;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "security_settings", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> securitySettings;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static UserSetting defaults(User user, String displayName, String department) {
        Instant now = Instant.now();
        UserSetting setting = new UserSetting();
        setting.user = user;
        setting.displayName = displayName;
        setting.department = department;
        setting.alertRules = new LinkedHashMap<>();
        setting.notificationSettings = defaultNotificationSettings();
        setting.viewPreferences = new LinkedHashMap<>();
        setting.securitySettings = new LinkedHashMap<>(Map.of(
                "refreshCookie", true,
                "autoLoginPolicy", "refresh_token_only"
        ));
        setting.createdAt = now;
        setting.updatedAt = now;
        return setting;
    }

    public void updateProfile(String displayName, String department) {
        if (displayName != null) {
            this.displayName = displayName;
        }
        if (department != null) {
            this.department = department;
        }
        touch();
    }

    public void updateAlertRules(Map<String, Object> alertRules) {
        this.alertRules = alertRules == null ? new LinkedHashMap<>() : new LinkedHashMap<>(alertRules);
        touch();
    }

    public void updateNotificationSettings(Map<String, Object> notificationSettings) {
        this.notificationSettings = notificationSettings == null
                ? defaultNotificationSettings()
                : new LinkedHashMap<>(notificationSettings);
        touch();
    }

    public void updateViewPreferences(Map<String, Object> viewPreferences) {
        this.viewPreferences = viewPreferences == null ? new LinkedHashMap<>() : new LinkedHashMap<>(viewPreferences);
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    private static Map<String, Object> defaultNotificationSettings() {
        return new LinkedHashMap<>(Map.of(
                "channels", new LinkedHashMap<>(Map.of(
                        "email", true,
                        "inApp", true,
                        "teams", false
                )),
                "briefingTime", "08:30",
                "eventImmediate", true
        ));
    }
}
