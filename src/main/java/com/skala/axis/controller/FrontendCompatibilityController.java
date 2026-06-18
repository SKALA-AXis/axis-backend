/*
 * 작성일: 2026-05-11
 * 작성자: 박진
 * 변경이력:
 *   2026-05-11 박진 — 프론트 기반 대량 수정으로 호환 컨트롤러 작성, 이후 목업 삭제·챗봇 고도화
 *   2026-05-19 최종민 — DB 정규화에 맞춰 수정
 *   2026-05-22 안가은 — 홈화면 그래프 데이터 연동
 */
package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import com.skala.axis.service.BriefingReportService;
import com.skala.axis.service.CardNewsService;
import com.skala.axis.service.DashboardKeywordTrendChartService;
import com.skala.axis.service.DashboardStockChartService;
import com.skala.axis.service.RawArticleQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Temporary compatibility endpoints for the current React UI.
 *
 * The OpenAPI contract remains under /api/**. These root-level endpoints keep
 * the existing frontend repositories working while they are migrated to the
 * canonical API paths.
 */
@RestController
@RequiredArgsConstructor
public class FrontendCompatibilityController {
    private final CardNewsService cardNewsService;
    private final BriefingReportService briefingReportService;
    private final DashboardStockChartService dashboardStockChartService;
    private final DashboardKeywordTrendChartService dashboardKeywordTrendChartService;
    private final RawArticleQueryService rawArticleQueryService;

    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDashboard() {
        Map<String, Object> dashboardSummary = emptyDashboardSummary();
        dashboardStockChartService.applyDailyRateChart(dashboardSummary);
        dashboardKeywordTrendChartService.applyKeywordTrendChart(dashboardSummary);
        return ResponseEntity.ok(ApiResponse.success(dashboardSummary));
    }

    @GetMapping("/briefings")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getBriefings() {
        return briefingReportService.findOverview(LocalDate.now())
                .map(payload -> ResponseEntity.ok(ApiResponse.success(payload)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(ApiResponse.<Map<String, Object>>error("BRIEFING_REPORT_UNAVAILABLE", "저장된 브리핑 결과가 없습니다.")));
    }

    @GetMapping("/alerts")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getAlerts() {
        return ResponseEntity.ok(ApiResponse.success(emptyAlerts()));
    }

    @GetMapping("/peers")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPeers() {
        return ResponseEntity.ok(ApiResponse.success(emptyPeers()));
    }

    @GetMapping("/raw-articles")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getRawArticles(@RequestParam Map<String, String> params) {
        return ResponseEntity.ok(ApiResponse.success(rawArticleQueryService.rawArticleItems(params)));
    }

    @GetMapping("/issues")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getIssues() {
        return ResponseEntity.ok(ApiResponse.success(
                cardNewsService.getAll(null, null, null).stream()
                        .map(card -> Map.<String, Object>of(
                                "id", card.getId(),
                                "peerId", card.getPeerId() == null ? "" : card.getPeerId(),
                                "peerName", peerName(card.getPeerId()),
                                "title", card.getTitle() == null ? "" : card.getTitle(),
                                "summaryLines", card.getSummaryLines() == null ? List.of() : card.getSummaryLines(),
                                "importance", issueImportance(card.getImportance(), card.getImportanceScore()),
                                "createdAt", card.getCreatedAt() == null ? "" : card.getCreatedAt().toString(),
                                "sourceUrl", card.getSourceUrl() == null ? "" : card.getSourceUrl()
                        ))
                        .toList()
        ));
    }

    private static Map<String, Object> emptyDashboardSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("trends", List.of());
        summary.put("articles", List.of());
        summary.put("keywords", List.of());
        summary.put("keywordSearchPoints", List.of());
        summary.put("keywordSeries", List.of());
        summary.put("keywordInsights", List.of());
        summary.put("stockPoints", List.of());
        summary.put("stockRatePoints", List.of());
        summary.put("notifications", List.of());
        summary.put("keywordNewsCount", "0");
        return summary;
    }

    private static Map<String, Object> emptyAlerts() {
        return Map.of(
                "rules", List.of(),
                "history", List.of(),
                "conditionOptions", List.of(),
                "channelOptions", List.of()
        );
    }

    private static Map<String, Object> emptyPeers() {
        return Map.of(
                "peers", List.of(),
                "periodLabels", Map.of("yearly", "연간", "quarterly", "분기", "monthly", "월간"),
                "periodDescriptions", Map.of(
                        "yearly", "연간 분석 데이터가 연결되면 표시됩니다.",
                        "quarterly", "분기 분석 데이터가 연결되면 표시됩니다.",
                        "monthly", "월간 분석 데이터가 연결되면 표시됩니다."
                ),
                "analyses", Map.of()
        );
    }

    private static String issueImportance(String importance, Float score) {
        if ("urgent".equals(importance) || "notable".equals(importance) || "reference".equals(importance)) {
            return importance;
        }
        if (score != null && score >= 0.85f) return "urgent";
        if (score != null && score >= 0.6f) return "notable";
        return "reference";
    }

    private static String peerName(String peerId) {
        if ("samsung_sds".equals(peerId)) return "삼성SDS";
        if ("lg_cns".equals(peerId)) return "LG CNS";
        if ("hyundai_autoever".equals(peerId)) return "현대오토에버";
        if ("posco_dx".equals(peerId)) return "포스코DX";
        return peerId == null || peerId.isBlank() ? "Peer사" : peerId;
    }
}
