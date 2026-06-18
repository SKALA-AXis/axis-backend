/*
 * 작성일: 2026-05-06
 * 작성자: 박진
 * 변경이력:
 *   2026-05-06 박진 — 관리자 컨트롤러 초안 작성, 이후 로그인/회원가입·챗봇 로직 반영
 *   2026-05-29 안가은 — 카드뉴스 소프트삭제·관리자 감사로그 API 추가
 */
package com.skala.axis.controller;

import com.skala.axis.config.AuthPrincipal;
import com.skala.axis.dto.ApiResponse;
import com.skala.axis.dto.auth.UserStatusUpdateRequest;
import com.skala.axis.dto.admin.AdminCardNewsStatusUpdateRequest;
import com.skala.axis.service.AdminAuditLogService;
import com.skala.axis.service.AdminCardNewsService;
import com.skala.axis.service.AdminUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;
import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {
    private final AdminUserService adminUserService;
    private final AdminCardNewsService adminCardNewsService;
    private final AdminAuditLogService adminAuditLogService;

    @GetMapping("/users")
    public ResponseEntity<ApiResponse<Map<String, Object>>> adminListUsers() {
        return ResponseEntity.ok(ApiResponse.success(adminUserService.listUsers()));
    }

    @GetMapping("/users/{userId}")
    public ResponseEntity<ApiResponse<Object>> adminGetUser(@PathVariable UUID userId) {
        return ResponseEntity.ok(ApiResponse.success(adminUserService.user(userId)));
    }

    @PatchMapping("/users/{userId}/status")
    public ResponseEntity<ApiResponse<Object>> adminUpdateUserStatus(
            @PathVariable UUID userId,
            @RequestBody UserStatusUpdateRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(adminUserService.changeStatus(userId, request.status())));
    }

    @GetMapping("/peers")
    public ResponseEntity<ApiResponse<Object>> adminListPeers() {
        return ResponseEntity.ok(ApiResponse.success(List.of()));
    }

    @PostMapping("/peers")
    public ResponseEntity<ApiResponse<Map<String, Object>>> adminCreatePeer(@RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(operationUnavailable("peer_store_unavailable")));
    }

    @PutMapping("/peers/{peerId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> adminUpdatePeer(@PathVariable String peerId, @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(ApiResponse.success(notFound("peer_id", peerId)));
    }

    @DeleteMapping("/peers/{peerId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> adminDeletePeer(@PathVariable String peerId) {
        return ResponseEntity.ok(ApiResponse.success(notFound("peer_id", peerId)));
    }

    @GetMapping("/sources")
    public ResponseEntity<ApiResponse<Object>> adminListSources() {
        return ResponseEntity.ok(ApiResponse.success(List.of()));
    }

    @PutMapping("/sources/{sourceId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> adminUpdateSource(@PathVariable String sourceId, @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(ApiResponse.success(notFound("source_id", sourceId)));
    }

    @GetMapping("/prompts")
    public ResponseEntity<ApiResponse<Object>> adminListPrompts() {
        return ResponseEntity.ok(ApiResponse.success(List.of()));
    }

    @PutMapping("/prompts/{promptId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> adminUpdatePrompt(@PathVariable String promptId, @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(ApiResponse.success(notFound("prompt_id", promptId)));
    }

    @GetMapping("/scheduler")
    public ResponseEntity<ApiResponse<Object>> adminListSchedulerJobs() {
        return ResponseEntity.ok(ApiResponse.success(List.of()));
    }

    @PutMapping("/scheduler/{jobId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> adminUpdateSchedulerJob(@PathVariable String jobId, @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(ApiResponse.success(notFound("job_id", jobId)));
    }

    @GetMapping("/usage")
    public ResponseEntity<ApiResponse<Map<String, Object>>> adminGetUsage(@RequestParam(defaultValue = "today") String period) {
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "period", period,
                "items", List.of(),
                "total", 0
        )));
    }

    @PutMapping("/usage/limits")
    public ResponseEntity<ApiResponse<Map<String, Object>>> adminSetUsageLimits(@RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(ApiResponse.success(operationUnavailable("usage_limit_store_unavailable")));
    }

    @GetMapping("/cards")
    public ResponseEntity<ApiResponse<Map<String, Object>>> adminListCards(@RequestParam(required = false) String status) {
        return ResponseEntity.ok(ApiResponse.success(adminCardNewsService.listCards(status)));
    }

    @PatchMapping("/cards/{cardId}/status")
    public ResponseEntity<ApiResponse<Object>> adminUpdateCardStatus(
            @PathVariable String cardId,
            @RequestBody AdminCardNewsStatusUpdateRequest request,
            Authentication authentication
    ) {
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        return ResponseEntity.ok(ApiResponse.success(
                adminCardNewsService.updateStatus(cardId, request.status(), request.reason(), principal)
        ));
    }

    @GetMapping("/audit-logs")
    public ResponseEntity<ApiResponse<Map<String, Object>>> adminGetAuditLogs(@RequestParam Map<String, String> params) {
        return ResponseEntity.ok(ApiResponse.success(adminAuditLogService.listLogs()));
    }

    private static Map<String, Object> operationUnavailable(String resultKind) {
        return Map.of("status", "failed", "result_kind", resultKind);
    }

    private static Map<String, Object> notFound(String key, String value) {
        return Map.of(
                key, value,
                "status", "not_found",
                "result_kind", "no_saved_" + key
        );
    }
}
