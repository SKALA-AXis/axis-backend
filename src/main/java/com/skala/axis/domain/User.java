/*
 * 작성일: 2026-05-21
 * 작성자: 박진
 * 변경이력:
 *   2026-05-21 박진 — 로그인/회원가입 기능으로 User 엔티티 추가, 이후 카드뉴스·알림 설정 및 중요 알림 키워드 사용자 설정 반영
 */
package com.skala.axis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {
    public static final List<String> DEFAULT_IMPORTANT_KEYWORDS = List.of("수주", "계약", "실적", "투자");

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 320)
    private String email;

    @Column(name = "email_domain", nullable = false, length = 120)
    private String emailDomain;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "notification_preferences", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> notificationPreferences;

    public static User pending(String email, String passwordHash) {
        Instant now = Instant.now();
        User user = new User();
        user.email = email;
        user.emailDomain = email.substring(email.indexOf('@') + 1);
        user.passwordHash = passwordHash;
        user.status = UserStatus.PENDING;
        user.role = UserRole.USER;
        user.emailVerified = false;
        user.failedLoginCount = 0;
        user.notificationPreferences = defaultNotificationPreferences();
        user.createdAt = now;
        user.updatedAt = now;
        return user;
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    public void activateEmail() {
        this.status = UserStatus.ACTIVE;
        this.emailVerified = true;
        touch();
    }

    public void recordLoginSuccess() {
        this.failedLoginCount = 0;
        this.lockedUntil = null;
        this.lastLoginAt = Instant.now();
        touch();
    }

    public void recordLoginFailure(int maxFailures, long lockMinutes) {
        this.failedLoginCount += 1;
        if (this.failedLoginCount >= maxFailures) {
            this.lockedUntil = Instant.now().plusSeconds(lockMinutes * 60);
        }
        touch();
    }

    public void changePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
        this.failedLoginCount = 0;
        this.lockedUntil = null;
        touch();
    }

    public void changeStatus(UserStatus status) {
        this.status = status;
        if (status != UserStatus.ACTIVE) {
            this.lockedUntil = null;
        }
        touch();
    }

    public void promoteToAdmin() {
        this.role = UserRole.ADMIN;
        touch();
    }

    public void updateNotificationPreferences(Map<String, Object> preferences) {
        this.notificationPreferences = preferences == null
                ? defaultNotificationPreferences()
                : new LinkedHashMap<>(preferences);
        touch();
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    public static Map<String, Object> defaultNotificationPreferences() {
        Map<String, Object> preferences = new LinkedHashMap<>();
        preferences.put("enabled", true);
        preferences.put("importantEnabled", true);
        preferences.put("importantKeywords", DEFAULT_IMPORTANT_KEYWORDS);
        preferences.put("keywords", List.of());
        return preferences;
    }
}
