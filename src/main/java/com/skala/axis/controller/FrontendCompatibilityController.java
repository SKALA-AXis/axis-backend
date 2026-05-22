package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import com.skala.axis.service.ApiContractFixtureService;
import com.skala.axis.service.DashboardKeywordTrendChartService;
import com.skala.axis.service.DashboardStockChartService;
import com.skala.axis.service.RawArticleQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final DashboardStockChartService dashboardStockChartService;
    private final DashboardKeywordTrendChartService dashboardKeywordTrendChartService;
    private final RawArticleQueryService rawArticleQueryService;

    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDashboard() {
        Map<String, Object> dashboardSummary = fixture.frontendDashboard();
        dashboardStockChartService.applyDailyRateChart(dashboardSummary);
        dashboardKeywordTrendChartService.applyKeywordTrendChart(dashboardSummary);
        return ResponseEntity.ok(ApiResponse.success(dashboardSummary));
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
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getRawArticles(@RequestParam Map<String, String> params) {
        return ResponseEntity.ok(ApiResponse.success(rawArticleQueryService.rawArticleItems(params)));
    }

    @GetMapping("/issues")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getIssues() {
        return ResponseEntity.ok(ApiResponse.success(fixture.frontendIssues()));
    }
}
