package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import com.skala.axis.service.ApiContractFixtureService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {
    private final ApiContractFixtureService fixture;

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> listNotifications(
            @RequestParam(defaultValue = "false") boolean unread_only,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return ResponseEntity.ok(ApiResponse.success(fixture.notifications()));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<ApiResponse<Map<String, Object>>> markNotificationAsRead(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.success(fixture.markNotificationAsRead(id)));
    }

    @DeleteMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> clearNotifications() {
        return ResponseEntity.ok(ApiResponse.success(fixture.clearNotifications()));
    }
}
