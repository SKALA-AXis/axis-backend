package com.skala.axis.service;

import com.skala.axis.domain.UserNotification;
import com.skala.axis.repository.UserNotificationRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserNotificationService {
    private final AuthService authService;
    private final UserNotificationRepository notificationRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> list(UUID userId, boolean unreadOnly, int limit) {
        authService.requireUser(userId);
        List<UserNotification> notifications = unreadOnly
                ? notificationRepository.findByUserIdAndReadAtIsNullOrderByCreatedAtDesc(userId)
                : notificationRepository.findByUserIdOrderByCreatedAtDesc(userId);
        List<Map<String, Object>> items = notifications.stream()
                .limit(Math.max(1, limit))
                .map(this::toItem)
                .toList();
        long unreadCount = notifications.stream().filter(notification -> notification.getReadAt() == null).count();
        return Map.of("items", items, "unread_count", unreadCount);
    }

    @Transactional
    public Map<String, Object> markRead(UUID userId, UUID notificationId) {
        UserNotification notification = notificationRepository.findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new EntityNotFoundException("notification not found"));
        notification.markRead();
        return toItem(notification);
    }

    @Transactional
    public Map<String, Object> clear(UUID userId) {
        authService.requireUser(userId);
        notificationRepository.deleteByUserId(userId);
        return Map.of("cleared", true);
    }

    private Map<String, Object> toItem(UserNotification notification) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", notification.getId().toString());
        item.put("type", notification.getNotificationType());
        item.put("title", notification.getTitle());
        item.put("message", notification.getMessage());
        item.put("target", notification.getTargetView());
        item.put("target_resource_id", notification.getTargetResourceId());
        item.put("payload", notification.getPayload());
        item.put("read", notification.getReadAt() != null);
        item.put("read_at", notification.getReadAt());
        item.put("created_at", notification.getCreatedAt());
        return item;
    }
}
