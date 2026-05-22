package com.skala.axis.controller;

import com.skala.axis.config.AuthProperties;
import com.skala.axis.config.AuthSecurity;
import com.skala.axis.domain.User;
import com.skala.axis.dto.ApiResponse;
import com.skala.axis.service.UserNotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/me/notification-preferences")
@RequiredArgsConstructor
public class MyNotificationPreferenceController {
    private final AuthProperties authProperties;
    private final UserNotificationService userNotificationService;

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPreferences(Authentication authentication) {
        if (authProperties.isEnforce()) {
            return ResponseEntity.ok(ApiResponse.success(userNotificationService.preferences(AuthSecurity.requireUserId(authentication))));
        }
        return ResponseEntity.ok(ApiResponse.success(User.defaultNotificationPreferences()));
    }

    @PatchMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> updatePreferences(
            @RequestBody(required = false) Map<String, Object> request,
            Authentication authentication
    ) {
        if (authProperties.isEnforce()) {
            return ResponseEntity.ok(ApiResponse.success(userNotificationService.updatePreferences(AuthSecurity.requireUserId(authentication), request)));
        }
        return ResponseEntity.ok(ApiResponse.success(request == null ? User.defaultNotificationPreferences() : request));
    }
}
