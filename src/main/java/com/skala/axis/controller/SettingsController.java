package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import com.skala.axis.service.ApiContractFixtureService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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

    @GetMapping("/alert-rules")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMyAlertRules() {
        return ResponseEntity.ok(ApiResponse.success(fixture.alertRules()));
    }

    @PutMapping("/alert-rules")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateMyAlertRules(@RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(ApiResponse.success(fixture.updatedResult()));
    }

    @GetMapping("/notifications")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getNotificationSettings() {
        return ResponseEntity.ok(ApiResponse.success(fixture.notificationSettings()));
    }

    @PutMapping("/notifications")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateNotificationSettings(@RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(ApiResponse.success(fixture.updatedResult()));
    }

    @PutMapping("/profile")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateProfile(@RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(ApiResponse.success(fixture.userProfile(request)));
    }

    @PutMapping("/password")
    public ResponseEntity<ApiResponse<Map<String, Object>>> changePassword(@RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(ApiResponse.success(fixture.changedResult()));
    }

    @GetMapping("/access-logs")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMyAccessLogs() {
        return ResponseEntity.ok(ApiResponse.success(fixture.accessLogs()));
    }
}
