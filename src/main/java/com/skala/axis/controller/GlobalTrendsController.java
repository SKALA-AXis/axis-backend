/*
 * 작성일: 2026-05-15
 * 작성자: 최종민
 * 변경이력:
 *   2026-05-15 최종민 — /api/global/trends/run 을 axis-ai 와 연동하고 GET /api/global/trends 조회 API 추가
 *   2026-06-11 박진 — 챗봇 AI 연동 보강
 */
package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import com.skala.axis.exception.AiServerException;
import com.skala.axis.service.AgentResponseGuard;
import com.skala.axis.service.AiClientService;
import com.skala.axis.service.GlobalTrendsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * GlobalTrends endpoint.
 *
 * <p>{@code POST /api/global/trends/run} — axis-ai 의 GlobalTrendsAgent 위임.
 * 5-phase (Snapshot 산식 / Trend Detection 산식 / Impact Mapping LLM / Forecast LLM /
 * Synthesis LLM). 글로벌 카드 부재 시 graceful 빈 응답 + warning.</p>
 *
 * <p>spec: {@code axis-ai/design/30-analysis/global-trends.md}.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/global")
@RequiredArgsConstructor
public class GlobalTrendsController {
    private final AiClientService aiClientService;
    private final GlobalTrendsService globalTrendsService;

    /**
     * 저장된 글로벌 트렌드 조회 — ``global_industry_trends`` read-only.
     *
     * <p>query: {@code from}, {@code to} (YYYY-MM-DD), {@code limit}, {@code offset}.</p>
     */
    @GetMapping("/trends")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listGlobalTrends(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        return ResponseEntity.ok(ApiResponse.success(globalTrendsService.listTrends(from, to, limit, offset)));
    }

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
            AgentResponseGuard.requireSuccess("GLOBAL_TRENDS", result);
            log.info("GlobalTrends | companies={} confidence={}",
                    result.get("company_ids"), result.get("confidence"));
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (AiServerException e) {
            log.warn("GlobalTrends | axis-ai 호출 실패 | code={} error={}", e.getCode(), e.getMessage());
            return ResponseEntity.status(e.getStatus())
                    .body(ApiResponse.error(e.getCode(), AiServerException.CALL_FAILED_MESSAGE));
        }
    }
}
