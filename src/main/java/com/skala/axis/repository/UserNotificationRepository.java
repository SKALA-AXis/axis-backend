package com.skala.axis.repository;

import com.skala.axis.domain.UserNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserNotificationRepository extends JpaRepository<UserNotification, UUID> {
    List<UserNotification> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<UserNotification> findByUserIdAndReadAtIsNullOrderByCreatedAtDesc(UUID userId);

    Optional<UserNotification> findByIdAndUserId(UUID id, UUID userId);

    @Transactional
    void deleteByUserId(UUID userId);
}
