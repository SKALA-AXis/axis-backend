/*
 * 작성일: 2026-04-21
 * 작성자: 최종민
 * 변경이력:
 *   2026-04-21 최종민 — axis-backend 베이스라인 작성, 이후 대형 이벤트(수주·파트너십·M&A) 1회성 이메일 알림·데모 인젝트 추가
 *   2026-05-06 박진 — 백엔드 초안 완성 및 프론트 기반 대량 수정·챗봇 로직 반영
 */
package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import com.skala.axis.security.CronInternalAuth;
import com.skala.axis.service.EventAlertService;
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

    private final EventAlertService eventAlertService;
    private final CronInternalAuth cronInternalAuth;

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> listAlerts(
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return ResponseEntity.ok(ApiResponse.success(emptyAlerts()));
    }

    /**
     * 대형 이벤트 알림 수동/Cron 스캔 — 최근 후보 카드 전수 평가 후 게이트 통과분 1회 발송.
     * Bearer ${CRON_INTERNAL_TOKEN} 검증(CronInternalAuth). 자동 발송은 SchedulerConfig.
     */
    @PostMapping("/scan")
    public ResponseEntity<ApiResponse<Map<String, Object>>> scan(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        if (!cronInternalAuth.isAuthorized(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.success(Map.of("status", "unauthorized")));
        }
        EventAlertService.ScanResult result = eventAlertService.scanRecent("manual");
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "status", "ok",
                "candidates", result.candidates(),
                "sent", result.sent(),
                "skipped", result.skipped(),
                "failed", result.failed()
        )));
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
