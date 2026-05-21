package com.skala.axis.controller;

import com.skala.axis.config.AuthProperties;
import com.skala.axis.config.AuthSecurity;
import com.skala.axis.dto.ApiResponse;
import com.skala.axis.service.ApiContractFixtureService;
import com.skala.axis.service.UserNotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {
    private final ApiContractFixtureService fixture;
    private final AuthProperties authProperties;
    private final UserNotificationService userNotificationService;

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> listNotifications(
            @RequestParam(defaultValue = "false") boolean unread_only,
            @RequestParam(defaultValue = "20") int limit,
            Authentication authentication
    ) {
        if (authProperties.isEnforce()) {
            return ResponseEntity.ok(ApiResponse.success(userNotificationService.list(AuthSecurity.requireUserId(authentication), unread_only, limit)));
        }
        return ResponseEntity.ok(ApiResponse.success(fixture.notifications()));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<ApiResponse<Map<String, Object>>> markNotificationAsRead(
            @PathVariable String id,
            Authentication authentication
    ) {
        if (authProperties.isEnforce()) {
            return ResponseEntity.ok(ApiResponse.success(userNotificationService.markRead(AuthSecurity.requireUserId(authentication), UUID.fromString(id))));
        }
        return ResponseEntity.ok(ApiResponse.success(fixture.markNotificationAsRead(id)));
    }

    @DeleteMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> clearNotifications(Authentication authentication) {
        if (authProperties.isEnforce()) {
            return ResponseEntity.ok(ApiResponse.success(userNotificationService.clear(AuthSecurity.requireUserId(authentication))));
        }
        return ResponseEntity.ok(ApiResponse.success(fixture.clearNotifications()));
    }
}
