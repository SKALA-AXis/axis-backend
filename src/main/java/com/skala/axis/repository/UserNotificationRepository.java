package com.skala.axis.repository;

import com.skala.axis.domain.UserNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserNotificationRepository extends JpaRepository<UserNotification, UUID> {
    List<UserNotification> findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    List<UserNotification> findByUserIdAndReadAtIsNullAndDeletedAtIsNullOrderByCreatedAtDesc(UUID userId, Pageable pageable);

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
