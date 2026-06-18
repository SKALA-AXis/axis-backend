/*
 * 작성일: 2026-05-21
 * 작성자: 박진
 * 변경이력:
 *   2026-05-21 박진 — 로그인/회원가입 기능과 함께 사용자 알림 리포지토리 추가, 이후 알림 설정 및 챗봇 지원 보강
 */
package com.skala.axis.repository;

import com.skala.axis.domain.UserNotification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

public interface UserNotificationRepository extends JpaRepository<UserNotification, UUID> {
    Page<UserNotification> findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<UserNotification> findByUserIdAndReadAtIsNullAndDeletedAtIsNullOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Optional<UserNotification> findByIdAndUserIdAndDeletedAtIsNull(UUID id, UUID userId);

    boolean existsByUserIdAndDedupeKey(UUID userId, String dedupeKey);

    long countByUserIdAndReadAtIsNullAndDeletedAtIsNull(UUID userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update UserNotification n
               set n.readAt = current_timestamp,
                   n.updatedAt = current_timestamp
             where n.user.id = :userId
               and n.readAt is null
               and n.deletedAt is null
            """)
    int markAllRead(@Param("userId") UUID userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update UserNotification n
               set n.deletedAt = current_timestamp,
                   n.updatedAt = current_timestamp
             where n.user.id = :userId
               and n.deletedAt is null
               and (:includeUnread = true or n.readAt is not null)
            """)
    int softDeleteByUserId(@Param("userId") UUID userId, @Param("includeUnread") boolean includeUnread);
}
