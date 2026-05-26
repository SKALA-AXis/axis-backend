package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import com.skala.axis.dto.GlobalIndustryTrendResponse;
import com.skala.axis.exception.AiServerException;
import com.skala.axis.service.AiClientService;
import com.skala.axis.service.GlobalIndustryTrendService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * GlobalTrends endpoint.
 *
 * <p>{@code POST /api/global/trends/run} — axis-ai 의 GlobalTrendsAgent 위임.
 * 5-phase (Snapshot 산식 / Trend Detection 산식 / Impact Mapping LLM / Forecast LLM /
 * Synthesis LLM). 글로벌 카드 부재 시 graceful 빈 응답 + warning.</p>
 *
 * <p>{@code GET /api/global/trends*} — DB ({@code global_industry_trends}) 직접 조회.
 * cron ({@code axis-cron-global-trend}) 이 daily 1 회 upsert 한 결과를 frontend 가
 * 페이지 진입 시 즉시 받음. POST run (sync proxy, ₩600~1,000/회) 비용 회피용.</p>
 *
 * <p>spec: {@code axis-ai/design/30-analysis/global-trends.md}.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/global")
@RequiredArgsConstructor
public class GlobalTrendsController {
    private final AiClientService aiClientService;
    private final GlobalIndustryTrendService globalIndustryTrendService;

    /**
     * 글로벌 트렌드 분석 — axis-ai GlobalTrendsAgent 위임.
     *
     * <p>request body schema:
     * <pre>
     *   { "company_ids": ["nvidia", ...] | null,
     *     "focus_themes": ["agentic_ai", ...] | null,
     *     "window_days": 30,
     *     "sk_ax_business_lines": ["ai_managed", ...] | null }
     * </pre>
     *
     * <p>response: axis-ai 의 GlobalTrendsOutput 그대로 (analysis_period / snapshots /
     * trend_detections / impact_matrix / forecasts / final_one_liner / sk_ax_implication /
     * follow_up_questions / reasoning_trail / reasoning_steps / langfuse_trace_id /
     * risk_assumptions / confidence / sources_used / company_ids / provenance / warning).
     */
    @PostMapping("/trends/run")
    @SuppressWarnings("unchecked")
    public ResponseEntity<ApiResponse<Map<String, Object>>> runGlobalTrends(
            @RequestBody(required = false) Map<String, Object> request) {
        Map<String, Object> body = request != null ? request : Map.of();
        List<String> companyIds = body.get("company_ids") instanceof List<?> c
                ? (List<String>) c
                : null;
        List<String> focusThemes = body.get("focus_themes") instanceof List<?> f
                ? (List<String>) f
                : null;
        Integer windowDays = body.get("window_days") instanceof Number n ? n.intValue() : null;
        List<String> lines = body.get("sk_ax_business_lines") instanceof List<?> l
                ? (List<String>) l
                : null;

        try {
            Map<String, Object> result = aiClientService
                    .runGlobalTrends(companyIds, focusThemes, windowDays, lines)
                    .block();
            if (result == null) {
                log.warn("GlobalTrends | axis-ai 응답 null");
                return ResponseEntity.ok(ApiResponse.success(Map.of(
                        "warning", "axis-ai 응답 null",
                        "snapshots", List.of(),
                        "trend_detections", List.of(),
                        "impact_matrix", List.of(),
                        "forecasts", List.of()
                )));
            }
            log.info("GlobalTrends | companies={} confidence={}",
                    result.get("company_ids"), result.get("confidence"));
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (AiServerException e) {
            log.warn("GlobalTrends | axis-ai 호출 실패 | {}", e.getMessage());
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "warning", "axis-ai 호출 실패: " + e.getMessage(),
                    "snapshots", List.of(),
                    "trend_detections", List.of(),
                    "impact_matrix", List.of(),
                    "forecasts", List.of()
            )));
        }
    }

    /**
     * 글로벌 IT 트렌드 row 목록 — {@code global_industry_trends} 직접 조회.
     *
     * @param from  trend_date {@code >= from} (null 이면 to - 30 days)
     * @param to    trend_date {@code <= to} (null 이면 오늘)
     * @param limit row 최대 개수 (default 50, max 500)
     */
    @GetMapping("/trends")
    public ResponseEntity<ApiResponse<List<GlobalIndustryTrendResponse>>> listTrends(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        List<GlobalIndustryTrendResponse> rows = globalIndustryTrendService.getInRange(from, to, limit);
        log.info("GlobalTrends | GET /trends from={} to={} limit={} → {} rows",
                from, to, limit, rows.size());
        return ResponseEntity.ok(ApiResponse.success(rows));
    }

    /**
     * 가장 최근 trend_date 한 batch (cron 이 daily 1 회 만든 그날의 row 들).
     *
     * @param limit row 최대 개수 (default 50, max 500)
     */
    @GetMapping("/trends/latest")
    public ResponseEntity<ApiResponse<List<GlobalIndustryTrendResponse>>> latestTrends(
            @RequestParam(required = false, defaultValue = "50") int limit) {
        List<GlobalIndustryTrendResponse> rows = globalIndustryTrendService.getLatest(limit);
        log.info("GlobalTrends | GET /trends/latest limit={} → {} rows", limit, rows.size());
        return ResponseEntity.ok(ApiResponse.success(rows));
    }

    /**
     * 한 axis-ai 분석 호출 (source_analysis_id) 의 모든 row. Langfuse trace 매칭/
     * 디버깅용.
     */
    @GetMapping("/trends/by-source/{sourceAnalysisId}")
    public ResponseEntity<ApiResponse<List<GlobalIndustryTrendResponse>>> bySourceAnalysisId(
            @PathVariable String sourceAnalysisId) {
        List<GlobalIndustryTrendResponse> rows =
                globalIndustryTrendService.getBySourceAnalysisId(sourceAnalysisId);
        log.info("GlobalTrends | GET /trends/by-source/{} → {} rows",
                sourceAnalysisId, rows.size());
        return ResponseEntity.ok(ApiResponse.success(rows));
    }
}
