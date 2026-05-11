package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import com.skala.axis.service.ApiContractFixtureService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/alerts")
@RequiredArgsConstructor
public class AlertController {
    private final ApiContractFixtureService fixture;

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> listAlerts(
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return ResponseEntity.ok(ApiResponse.success(fixture.frontendAlerts()));
    }

    @PostMapping("/rules")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createAlertRule(@RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(fixture.alertRule(null, request)));
    }

    @PutMapping("/rules/{ruleId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateAlertRule(
            @PathVariable String ruleId,
            @RequestBody(required = false) Map<String, Object> request
    ) {
        return ResponseEntity.ok(ApiResponse.success(fixture.alertRule(ruleId, request)));
    }

    @DeleteMapping("/rules/{ruleId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteAlertRule(@PathVariable String ruleId) {
        return ResponseEntity.ok(ApiResponse.success(fixture.deletedResult("rule_id", ruleId)));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<ApiResponse<Map<String, Object>>> markAlertAsRead(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.success(fixture.markAlertAsRead(id)));
    }

    @GetMapping("/settings")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSettings() {
        return ResponseEntity.ok(ApiResponse.success(fixture.notificationSettings()));
    }

    @PutMapping("/settings")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateSettings(@RequestBody Map<String, Object> settings) {
        return ResponseEntity.ok(ApiResponse.success(fixture.updatedResult()));
    }
}
