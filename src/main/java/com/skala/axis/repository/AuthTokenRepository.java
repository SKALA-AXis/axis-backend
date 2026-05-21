package com.skala.axis.repository;

import com.skala.axis.domain.AuthToken;
import com.skala.axis.domain.AuthTokenType;
import com.skala.axis.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuthTokenRepository extends JpaRepository<AuthToken, UUID> {
    Optional<AuthToken> findByTokenHash(String tokenHash);

    List<AuthToken> findByUserAndTypeAndRevokedAtIsNull(User user, AuthTokenType type);

    @Modifying
    @Query("UPDATE AuthToken t SET t.revokedAt = :now WHERE t.tokenFamilyId = :familyId AND t.revokedAt IS NULL")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);
}
