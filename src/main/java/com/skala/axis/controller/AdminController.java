package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import com.skala.axis.dto.auth.UserStatusUpdateRequest;
import com.skala.axis.service.ApiContractFixtureService;
import com.skala.axis.service.AdminUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {
    private final ApiContractFixtureService fixture;
    private final AdminUserService adminUserService;

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
        return ResponseEntity.ok(ApiResponse.success(fixture.peers()));
    }

    @PostMapping("/peers")
    public ResponseEntity<ApiResponse<Map<String, Object>>> adminCreatePeer(@RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(fixture.createdResult()));
    }

    @PutMapping("/peers/{peerId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> adminUpdatePeer(@PathVariable String peerId, @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(ApiResponse.success(fixture.updatedResult("peer_id", peerId)));
    }

    @DeleteMapping("/peers/{peerId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> adminDeletePeer(@PathVariable String peerId) {
        return ResponseEntity.ok(ApiResponse.success(fixture.deletedResult("peer_id", peerId)));
    }

    @GetMapping("/sources")
    public ResponseEntity<ApiResponse<Object>> adminListSources() {
        return ResponseEntity.ok(ApiResponse.success(fixture.dataSources()));
    }

    @PutMapping("/sources/{sourceId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> adminUpdateSource(@PathVariable String sourceId, @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(ApiResponse.success(fixture.updatedResult("source_id", sourceId)));
    }

    @GetMapping("/prompts")
    public ResponseEntity<ApiResponse<Object>> adminListPrompts() {
        return ResponseEntity.ok(ApiResponse.success(fixture.prompts()));
    }

    @PutMapping("/prompts/{promptId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> adminUpdatePrompt(@PathVariable String promptId, @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(ApiResponse.success(fixture.updatedResult("prompt_id", promptId)));
    }

    @GetMapping("/scheduler")
    public ResponseEntity<ApiResponse<Object>> adminListSchedulerJobs() {
        return ResponseEntity.ok(ApiResponse.success(fixture.schedulerJobs()));
    }

    @PutMapping("/scheduler/{jobId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> adminUpdateSchedulerJob(@PathVariable String jobId, @RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(ApiResponse.success(fixture.updatedResult("job_id", jobId)));
    }

    @GetMapping("/usage")
    public ResponseEntity<ApiResponse<Map<String, Object>>> adminGetUsage(@RequestParam(defaultValue = "today") String period) {
        return ResponseEntity.ok(ApiResponse.success(fixture.usage(period)));
    }

    @PutMapping("/usage/limits")
    public ResponseEntity<ApiResponse<Map<String, Object>>> adminSetUsageLimits(@RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(ApiResponse.success(fixture.updatedResult()));
    }

    @GetMapping("/audit-logs")
    public ResponseEntity<ApiResponse<Map<String, Object>>> adminGetAuditLogs(@RequestParam Map<String, String> params) {
        return ResponseEntity.ok(ApiResponse.success(fixture.auditLogs()));
    }
}
