/*
 * 작성일: 2026-05-11
 * 작성자: 박진
 * 변경이력:
 *   2026-05-11 박진 — 프론트 연동 위한 백엔드 대량 수정 후 로그인/회원가입, 알림 설정, 챗봇 백엔드 지원 추가
 */
package com.skala.axis.controller;

import com.skala.axis.config.AuthProperties;
import com.skala.axis.config.AuthSecurity;
import com.skala.axis.dto.ApiResponse;
import com.skala.axis.service.UserNotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {
    private final AuthProperties authProperties;
    private final UserNotificationService userNotificationService;

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> listNotifications(
            @RequestParam(defaultValue = "false") boolean unread_only,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int page,
            Authentication authentication
    ) {
        if (authProperties.isEnforce()) {
            return ResponseEntity.ok(ApiResponse.success(userNotificationService.list(AuthSecurity.requireUserId(authentication), unread_only, limit, page)));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("items", List.of());
        response.put("unread_count", 0);
        response.put("unreadCount", 0);
        response.put("page", Math.max(0, page));
        response.put("limit", Math.max(1, limit));
        response.put("size", Math.max(1, limit));
        response.put("total", 0);
        response.put("total_count", 0);
        response.put("totalCount", 0);
        response.put("totalPages", 0);
        response.put("total_pages", 0);
        response.put("hasNext", false);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<Map<String, Object>>> unreadCount(Authentication authentication) {
        if (authProperties.isEnforce()) {
            return ResponseEntity.ok(ApiResponse.success(userNotificationService.unreadCount(AuthSecurity.requireUserId(authentication))));
        }
        return ResponseEntity.ok(ApiResponse.success(Map.of("count", 0)));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<ApiResponse<Map<String, Object>>> markNotificationAsRead(
            @PathVariable String id,
            Authentication authentication
    ) {
        if (authProperties.isEnforce()) {
            return ResponseEntity.ok(ApiResponse.success(userNotificationService.markRead(AuthSecurity.requireUserId(authentication), UUID.fromString(id))));
        }
        return ResponseEntity.ok(ApiResponse.success(notFound(id)));
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<ApiResponse<Map<String, Object>>> patchNotificationAsRead(
            @PathVariable String id,
            Authentication authentication
    ) {
        return markNotificationAsRead(id, authentication);
    }

    @PatchMapping("/read-all")
    public ResponseEntity<ApiResponse<Map<String, Object>>> markAllNotificationsAsRead(Authentication authentication) {
        if (authProperties.isEnforce()) {
            return ResponseEntity.ok(ApiResponse.success(userNotificationService.markAllRead(AuthSecurity.requireUserId(authentication))));
        }
        return ResponseEntity.ok(ApiResponse.success(Map.of("updated_count", 0, "updatedCount", 0)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteNotification(
            @PathVariable String id,
            Authentication authentication
    ) {
        if (authProperties.isEnforce()) {
            return ResponseEntity.ok(ApiResponse.success(userNotificationService.deleteOne(AuthSecurity.requireUserId(authentication), UUID.fromString(id))));
        }
        return ResponseEntity.ok(ApiResponse.success(notFound(id)));
    }

    @DeleteMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> clearNotifications(
            @RequestParam(defaultValue = "READ") String scope,
            Authentication authentication
    ) {
        if (authProperties.isEnforce()) {
            return ResponseEntity.ok(ApiResponse.success(userNotificationService.clear(AuthSecurity.requireUserId(authentication), scope)));
        }
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "deleted_count", 0,
                "deletedCount", 0
        )));
    }

    private static Map<String, Object> notFound(String id) {
        return Map.of(
                "id", id,
                "status", "not_found",
                "result_kind", "no_saved_notification"
        );
    }
}
