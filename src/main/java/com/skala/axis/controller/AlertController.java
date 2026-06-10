package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/alerts")
@RequiredArgsConstructor
public class AlertController {

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> listAlerts(
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return ResponseEntity.ok(ApiResponse.success(emptyAlerts()));
    }

    @PostMapping("/rules")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createAlertRule(@RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(Map.of("status", "failed", "result_kind", "alert_store_unavailable")));
    }

    @PutMapping("/rules/{ruleId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateAlertRule(
            @PathVariable String ruleId,
            @RequestBody(required = false) Map<String, Object> request
    ) {
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "rule_id", ruleId,
                "status", "not_found",
                "result_kind", "no_saved_alert_rule"
        )));
    }

    @DeleteMapping("/rules/{ruleId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteAlertRule(@PathVariable String ruleId) {
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "rule_id", ruleId,
                "status", "not_found",
                "result_kind", "no_saved_alert_rule"
        )));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<ApiResponse<Map<String, Object>>> markAlertAsRead(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "id", id,
                "status", "not_found",
                "result_kind", "no_saved_alert"
        )));
    }

    @GetMapping("/settings")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSettings() {
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "channels", List.of(),
                "quietHours", Map.of(),
                "rules", List.of()
        )));
    }

    @PutMapping("/settings")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateSettings(@RequestBody Map<String, Object> settings) {
        return ResponseEntity.ok()
                .body(ApiResponse.success(Map.of("status", "failed", "result_kind", "alert_store_unavailable")));
    }

    private static Map<String, Object> emptyAlerts() {
        return Map.of(
                "rules", List.of(),
                "history", List.of(),
                "conditionOptions", List.of(),
                "channelOptions", List.of()
        );
    }
}
