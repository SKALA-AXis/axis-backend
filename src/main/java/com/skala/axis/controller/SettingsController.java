package com.skala.axis.controller;

import com.skala.axis.config.AuthProperties;
import com.skala.axis.config.AuthSecurity;
import com.skala.axis.dto.ApiResponse;
import com.skala.axis.dto.auth.PasswordChangeRequest;
import com.skala.axis.service.ApiContractFixtureService;
import com.skala.axis.service.AuthService;
import com.skala.axis.service.RequestMetadata;
import com.skala.axis.service.UserSettingsService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {
    private final ApiContractFixtureService fixture;
    private final AuthProperties authProperties;
    private final AuthService authService;
    private final UserSettingsService userSettingsService;

    @GetMapping("/alert-rules")
    public ResponseEntity<ApiResponse<Object>> getMyAlertRules(Authentication authentication) {
        if (authProperties.isEnforce()) {
            return ResponseEntity.ok(ApiResponse.success(userSettingsService.alertRules(AuthSecurity.requireUserId(authentication))));
        }
        return ResponseEntity.ok(ApiResponse.success(fixture.alertRules()));
    }

    @PutMapping("/alert-rules")
    public ResponseEntity<ApiResponse<Object>> updateMyAlertRules(
            @RequestBody(required = false) Map<String, Object> request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        if (authProperties.isEnforce()) {
            return ResponseEntity.ok(ApiResponse.success(userSettingsService.updateAlertRules(
                    AuthSecurity.requireUserId(authentication),
                    request,
                    RequestMetadata.from(servletRequest)
            )));
        }
        return ResponseEntity.ok(ApiResponse.success(fixture.updatedResult()));
    }

    @GetMapping("/notifications")
    public ResponseEntity<ApiResponse<Object>> getNotificationSettings(Authentication authentication) {
        if (authProperties.isEnforce()) {
            return ResponseEntity.ok(ApiResponse.success(userSettingsService.notificationSettings(AuthSecurity.requireUserId(authentication))));
        }
        return ResponseEntity.ok(ApiResponse.success(fixture.notificationSettings()));
    }

    @PutMapping("/notifications")
    public ResponseEntity<ApiResponse<Object>> updateNotificationSettings(
            @RequestBody(required = false) Map<String, Object> request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        if (authProperties.isEnforce()) {
            return ResponseEntity.ok(ApiResponse.success(userSettingsService.updateNotificationSettings(
                    AuthSecurity.requireUserId(authentication),
                    request,
                    RequestMetadata.from(servletRequest)
            )));
        }
        return ResponseEntity.ok(ApiResponse.success(fixture.updateNotificationSettings(request)));
    }

    @GetMapping("/profile")
    public ResponseEntity<ApiResponse<Object>> getProfile(Authentication authentication) {
        if (authProperties.isEnforce()) {
            return ResponseEntity.ok(ApiResponse.success(userSettingsService.profile(AuthSecurity.requireUserId(authentication))));
        }
        return ResponseEntity.ok(ApiResponse.success(fixture.userProfile(Map.of())));
    }

    @PutMapping("/profile")
    public ResponseEntity<ApiResponse<Object>> updateProfile(
            @RequestBody(required = false) Map<String, Object> request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        if (authProperties.isEnforce()) {
            return ResponseEntity.ok(ApiResponse.success(userSettingsService.updateProfile(
                    AuthSecurity.requireUserId(authentication),
                    request,
                    RequestMetadata.from(servletRequest)
            )));
        }
        return ResponseEntity.ok(ApiResponse.success(fixture.userProfile(request)));
    }

    @GetMapping("/view-preferences")
    public ResponseEntity<ApiResponse<Object>> getViewPreferences(Authentication authentication) {
        if (authProperties.isEnforce()) {
            return ResponseEntity.ok(ApiResponse.success(userSettingsService.viewPreferences(AuthSecurity.requireUserId(authentication))));
        }
        return ResponseEntity.ok(ApiResponse.success(fixture.viewPreferences()));
    }

    @PutMapping("/view-preferences")
    public ResponseEntity<ApiResponse<Object>> updateViewPreferences(
            @RequestBody(required = false) Map<String, Object> request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        if (authProperties.isEnforce()) {
            return ResponseEntity.ok(ApiResponse.success(userSettingsService.updateViewPreferences(
                    AuthSecurity.requireUserId(authentication),
                    request,
                    RequestMetadata.from(servletRequest)
            )));
        }
        return ResponseEntity.ok(ApiResponse.success(fixture.updateViewPreferences(request)));
    }

    @PutMapping("/password")
    public ResponseEntity<ApiResponse<Object>> changePassword(
            @RequestBody(required = false) PasswordChangeRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        if (authProperties.isEnforce()) {
            return ResponseEntity.ok(ApiResponse.success(authService.changePassword(
                    AuthSecurity.requireUserId(authentication),
                    request,
                    RequestMetadata.from(servletRequest)
            )));
        }
        return ResponseEntity.ok(ApiResponse.success(fixture.changedResult()));
    }

    @GetMapping("/access-logs")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMyAccessLogs(Authentication authentication) {
        if (authProperties.isEnforce()) {
            return ResponseEntity.ok(ApiResponse.success(userSettingsService.accessLogs(AuthSecurity.requireUserId(authentication))));
        }
        return ResponseEntity.ok(ApiResponse.success(fixture.accessLogs()));
    }
}
