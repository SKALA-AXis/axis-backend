package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import com.skala.axis.service.ApiContractFixtureService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Temporary compatibility endpoints for the current React mock UI.
 *
 * The OpenAPI contract remains under /api/**. These root-level endpoints keep
 * the existing frontend repositories working while they are migrated to the
 * canonical API paths.
 */
@RestController
@RequiredArgsConstructor
public class FrontendCompatibilityController {
    private final ApiContractFixtureService fixture;

    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDashboard() {
        return ResponseEntity.ok(ApiResponse.success(fixture.frontendDashboard()));
    }

    @GetMapping("/briefings")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getBriefings() {
        return ResponseEntity.ok(ApiResponse.success(fixture.frontendBriefings()));
    }

    @GetMapping("/alerts")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getAlerts() {
        return ResponseEntity.ok(ApiResponse.success(fixture.frontendAlerts()));
    }

    @GetMapping("/peers")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPeers() {
        return ResponseEntity.ok(ApiResponse.success(fixture.frontendPeers()));
    }

    @GetMapping("/raw-articles")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getRawArticles() {
        return ResponseEntity.ok(ApiResponse.success(fixture.rawArticles()));
    }

    @GetMapping("/issues")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getIssues() {
        return ResponseEntity.ok(ApiResponse.success(fixture.frontendIssues()));
    }
}
