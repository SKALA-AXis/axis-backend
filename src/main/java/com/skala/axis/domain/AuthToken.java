package com.skala.axis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "auth_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuthToken {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AuthTokenType type;

    @Column(name = "token_hash", nullable = false, unique = true, length = 128)
    private String tokenHash;

    @Column(name = "token_family_id")
    private UUID tokenFamilyId;

    @Column(name = "previous_token_id")
    private UUID previousTokenId;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static AuthToken emailVerification(User user, String tokenHash, Instant expiresAt, String userAgent, String ipAddress) {
        AuthToken token = new AuthToken();
        token.user = user;
        token.type = AuthTokenType.EMAIL_VERIFICATION;
        token.tokenHash = tokenHash;
        token.userAgent = userAgent;
        token.ipAddress = ipAddress;
        token.expiresAt = expiresAt;
        token.createdAt = Instant.now();
        return token;
    }

    public static AuthToken refresh(
            User user,
            String tokenHash,
            UUID tokenFamilyId,
            UUID previousTokenId,
            Instant expiresAt,
            String userAgent,
            String ipAddress
    ) {
        AuthToken token = new AuthToken();
        token.user = user;
        token.type = AuthTokenType.REFRESH;
        token.tokenHash = tokenHash;
        token.tokenFamilyId = tokenFamilyId;
        token.previousTokenId = previousTokenId;
        token.userAgent = userAgent;
        token.ipAddress = ipAddress;
        token.expiresAt = expiresAt;
        token.createdAt = Instant.now();
        return token;
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public void markUsed() {
        this.usedAt = Instant.now();
    }

    public void revoke() {
        if (this.revokedAt == null) {
            this.revokedAt = Instant.now();
        }
    }
}
