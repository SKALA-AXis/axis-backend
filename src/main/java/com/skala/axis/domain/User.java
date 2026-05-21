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

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {
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

    public void touch() {
        this.updatedAt = Instant.now();
    }
}
