/*
 * 작성일: 2026-05-06
 * 작성자: 박진
 * 변경이력:
 *   2026-05-06 박진 — Backend 초안부터 로그인/회원가입·챗봇 백엔드 지원까지 설정 컨트롤러 작업
 *   2026-06-16 심유정 — 사용자 전략 컨텍스트 설정 API 추가
 */
package com.skala.axis.controller;

import com.skala.axis.config.AuthProperties;
import com.skala.axis.config.AuthSecurity;
import com.skala.axis.dto.ApiResponse;
import com.skala.axis.dto.auth.PasswordChangeRequest;
import com.skala.axis.service.AuthService;
import com.skala.axis.service.RequestMetadata;
import com.skala.axis.service.UserSettingsService;
import com.skala.axis.service.UserStrategyContextService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {
    private final AuthProperties authProperties;
    private final AuthService authService;
    private final UserSettingsService userSettingsService;
    private final UserStrategyContextService userStrategyContextService;

    @GetMapping("/alert-rules")
    public ResponseEntity<ApiResponse<Object>> getMyAlertRules(Authentication authentication) {
        if (authProperties.isEnforce()) {
            return ResponseEntity.ok(ApiResponse.success(userSettingsService.alertRules(AuthSecurity.requireUserId(authentication))));
        }
        return ResponseEntity.ok(ApiResponse.success(Map.of("items", List.of())));
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
        return ResponseEntity.ok(ApiResponse.success(operationUnavailable("settings_store_unavailable")));
    }

    @GetMapping("/notifications")
    public ResponseEntity<ApiResponse<Object>> getNotificationSettings(Authentication authentication) {
        if (authProperties.isEnforce()) {
            return ResponseEntity.ok(ApiResponse.success(userSettingsService.notificationSettings(AuthSecurity.requireUserId(authentication))));
        }
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "channels", Map.of(),
                "quietHours", Map.of(),
                "rules", List.of()
        )));
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
        return ResponseEntity.ok(ApiResponse.success(operationUnavailable("settings_store_unavailable")));
    }

    @GetMapping("/profile")
    public ResponseEntity<ApiResponse<Object>> getProfile(Authentication authentication) {
        if (authProperties.isEnforce()) {
            return ResponseEntity.ok(ApiResponse.success(userSettingsService.profile(AuthSecurity.requireUserId(authentication))));
        }
        return ResponseEntity.ok(ApiResponse.success(operationUnavailable("profile_store_unavailable")));
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
        return ResponseEntity.ok(ApiResponse.success(operationUnavailable("profile_store_unavailable")));
    }

    @GetMapping("/view-preferences")
    public ResponseEntity<ApiResponse<Object>> getViewPreferences(Authentication authentication) {
        if (authProperties.isEnforce()) {
            return ResponseEntity.ok(ApiResponse.success(userSettingsService.viewPreferences(AuthSecurity.requireUserId(authentication))));
        }
        return ResponseEntity.ok(ApiResponse.success(Map.of()));
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
        return ResponseEntity.ok(ApiResponse.success(operationUnavailable("settings_store_unavailable")));
    }

    @GetMapping("/strategy-contexts")
    public ResponseEntity<ApiResponse<Object>> getStrategyContexts(Authentication authentication) {
        if (authProperties.isEnforce()) {
            return ResponseEntity.ok(ApiResponse.success(userStrategyContextService.list(
                    AuthSecurity.requireUserId(authentication)
            )));
        }
        return ResponseEntity.ok(ApiResponse.success(Map.of("items", List.of())));
    }

    @PostMapping("/strategy-contexts")
    public ResponseEntity<ApiResponse<Object>> createStrategyContext(
            @RequestBody(required = false) Map<String, Object> request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        if (authProperties.isEnforce()) {
            return ResponseEntity.ok(ApiResponse.success(userStrategyContextService.create(
                    AuthSecurity.requireUserId(authentication),
                    request,
                    RequestMetadata.from(servletRequest)
            )));
        }
        return ResponseEntity.ok(ApiResponse.success(operationUnavailable("settings_store_unavailable")));
    }

    @PutMapping("/strategy-contexts/{contextId}")
    public ResponseEntity<ApiResponse<Object>> updateStrategyContext(
            @PathVariable UUID contextId,
            @RequestBody(required = false) Map<String, Object> request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        if (authProperties.isEnforce()) {
            return ResponseEntity.ok(ApiResponse.success(userStrategyContextService.update(
                    AuthSecurity.requireUserId(authentication),
                    contextId,
                    request,
                    RequestMetadata.from(servletRequest)
            )));
        }
        return ResponseEntity.ok(ApiResponse.success(operationUnavailable("settings_store_unavailable")));
    }

    @DeleteMapping("/strategy-contexts/{contextId}")
    public ResponseEntity<ApiResponse<Object>> deleteStrategyContext(
            @PathVariable UUID contextId,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        if (authProperties.isEnforce()) {
            return ResponseEntity.ok(ApiResponse.success(userStrategyContextService.delete(
                    AuthSecurity.requireUserId(authentication),
                    contextId,
                    RequestMetadata.from(servletRequest)
            )));
        }
        return ResponseEntity.ok(ApiResponse.success(operationUnavailable("settings_store_unavailable")));
    }

    @PostMapping(value = "/strategy-context-files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Object>> extractStrategyContextFile(
            @RequestParam("file") MultipartFile file,
            Authentication authentication
    ) {
        if (authProperties.isEnforce()) {
            AuthSecurity.requireUserId(authentication);
            return ResponseEntity.ok(ApiResponse.success(userStrategyContextService.extractFile(file)));
        }
        return ResponseEntity.ok(ApiResponse.success(operationUnavailable("settings_store_unavailable")));
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
        return ResponseEntity.ok(ApiResponse.success(operationUnavailable("password_change_unavailable")));
    }

    @GetMapping("/access-logs")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMyAccessLogs(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "5") int size
    ) {
        if (authProperties.isEnforce()) {
            return ResponseEntity.ok(ApiResponse.success(userSettingsService.accessLogs(AuthSecurity.requireUserId(authentication), page, size)));
        }
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "items", List.of(),
                "page", Math.max(0, page),
                "size", Math.max(1, size),
                "total", 0,
                "totalPages", 0
        )));
    }

    private static Map<String, Object> operationUnavailable(String resultKind) {
        return Map.of("status", "failed", "result_kind", resultKind);
    }
}
