package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import com.skala.axis.exception.AiServerException;
import com.skala.axis.security.CronInternalAuth;
import com.skala.axis.service.AgentResponseGuard;
import com.skala.axis.service.AiClientService;
import com.skala.axis.service.DashboardKeywordTrendChartService;
import com.skala.axis.service.DashboardStockChartService;
import com.skala.axis.service.TodayInsightCronRequestFactory;
import com.skala.axis.service.TodayInsightReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {
    private final DashboardStockChartService dashboardStockChartService;
    private final DashboardKeywordTrendChartService dashboardKeywordTrendChartService;
    private final AiClientService aiClientService;
    private final CronInternalAuth cronInternalAuth;
    private final TodayInsightReportService todayInsightReportService;
    private final AtomicBoolean todayInsightWarmupInFlight = new AtomicBoolean(false);

    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDashboardSummary(@RequestParam Map<String, String> params) {
        Map<String, Object> dashboardSummary = emptyDashboardSummary();
        dashboardStockChartService.applyDailyRateChart(dashboardSummary);
        dashboardKeywordTrendChartService.removeKeywordTrendChart(dashboardSummary);
        dashboardSummary.put("dataStatus", dashboardSummaryStatus(dashboardSummary));
        return ResponseEntity.ok(ApiResponse.success(dashboardSummary));
    }

    @GetMapping("/keyword-trends")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDashboardKeywordTrends() {
        return ResponseEntity.ok(ApiResponse.success(dashboardKeywordTrendChartService.getCachedKeywordTrendChart()));
    }

    @GetMapping("/today-insight")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getTodayInsight(@RequestParam Map<String, String> params) {
        Map<String, Object> request = todayInsightRequest(params, false, true);
        LocalDate anchorDate = parseAnchorDate(request.get("anchor_date"));

        // 사전 생성(cron 08:10 / warmup)된 today_insight_reports 가 있으면 DB 에서 바로 반환한다.
        // 화면 새로고침 hot-path 에서 단일 axis-ai pod 로의 동기 round-trip(.block)을 제거 —
        // 동시 접속 시 단일 ai 워커 직렬화로 인한 지연/먹통 완화. DB 는 ai 캐시와 동일 소스이며
        // (cron save=true 로 저장) GET(save=false)은 생성하지 않으므로 staleness 차이가 없다.
        Optional<Map<String, Object>> stored = todayInsightReportService.findLatestOnOrBefore(anchorDate);
        if (stored.isPresent()) {
            return ResponseEntity.ok(ApiResponse.success(stored.get()));
        }

        // 저장된 리포트가 아직 없을 때(콜드 스타트 등)만 axis-ai cache_only 경로로 조회한다.
        try {
            Map<String, Object> result = aiClientService.generateTodayInsight(request).block();
            if (result != null && !result.isEmpty()) {
                if (isTodayInsightStatusPlaceholder(result)) {
                    return todayInsightLatestOrStatus(anchorDate, result);
                }
                AgentResponseGuard.requireSuccess("TODAY_INSIGHT", result);
                return ResponseEntity.ok(ApiResponse.success(result));
            }
            log.warn("TodayInsight axis-ai 응답 비어 있음");
            throw new AiServerException(
                    "TODAY_INSIGHT_AI_EMPTY_RESPONSE",
                    "axis-ai returned empty response",
                    HttpStatus.BAD_GATEWAY
            );
        } catch (AiServerException e) {
            log.warn("TodayInsight axis-ai 호출 실패 | code={} error={}", e.getCode(), e.getMessage());
            return ResponseEntity.status(e.getStatus())
                    .body(ApiResponse.error(e.getCode(), AiServerException.CALL_FAILED_MESSAGE));
        }
    }

    @PostMapping("/today-insight/cron-generate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cronGenerateTodayInsight(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        if (!cronInternalAuth.isAuthorized(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.success(Map.of("status", "unauthorized")));
        }

        Map<String, Object> request = TodayInsightCronRequestFactory.dailyGenerateRequest(LocalDate.now());
        try {
            Map<String, Object> result = aiClientService.generateTodayInsight(request).block();
            AgentResponseGuard.requireSuccess("TODAY_INSIGHT", result);
            String headline = result == null ? "" : String.valueOf(result.getOrDefault("headline", ""));
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(Map.of(
                    "status", "accepted",
                    "anchor_date", request.get("anchor_date"),
                    "headline", headline,
                    "update_policy", "daily_0810_kst"
            )));
        } catch (AiServerException e) {
            log.error("TodayInsight cron-generate axis-ai 호출 실패 | anchor={} error={}",
                    request.get("anchor_date"), e.getMessage());
            return ResponseEntity.status(e.getStatus())
                    .body(ApiResponse.error(e.getCode(), AiServerException.CALL_FAILED_MESSAGE));
        } catch (Exception e) {
            log.error("TodayInsight cron-generate 실패 | anchor={} error={}",
                    request.get("anchor_date"), e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("TODAY_INSIGHT_CRON_FAILED", AiServerException.CALL_FAILED_MESSAGE));
        }
    }

    @PostMapping("/today-insight/warmup")
    public ResponseEntity<ApiResponse<Map<String, Object>>> warmupTodayInsight(@RequestParam Map<String, String> params) {
        boolean generateNow = boolParam(params, "generate", false)
                || boolParam(params, "force_refresh", false)
                || boolParam(params, "save", false);
        Map<String, Object> request = todayInsightRequest(params, true, !generateNow);
        if (!todayInsightWarmupInFlight.compareAndSet(false, true)) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(Map.of(
                    "status", "already_running",
                    "anchor_date", request.get("anchor_date"),
                    "cache_only", request.get("cache_only"),
                    "force_refresh", request.get("force_refresh"),
                    "save", request.get("save"),
                    "refresh_policy", request.get("refresh_policy"),
                    "update_policy", "daily_0810_kst"
            )));
        }
        aiClientService.generateTodayInsight(request)
                .doFinally(signalType -> todayInsightWarmupInFlight.set(false))
                .subscribe(
                        result -> log.info(
                                "TodayInsight warm-up 완료 | anchor={} headline={}",
                                request.get("anchor_date"),
                                result == null ? "" : result.getOrDefault("headline", "")
                        ),
                        e -> log.warn("TodayInsight warm-up 실패 | anchor={} error={}", request.get("anchor_date"), e.getMessage())
                );
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(Map.of(
                "status", "accepted",
                "anchor_date", request.get("anchor_date"),
                "cache_only", request.get("cache_only"),
                "force_refresh", request.get("force_refresh"),
                "save", request.get("save"),
                "refresh_policy", request.get("refresh_policy"),
                "update_policy", "daily_0810_kst"
        )));
    }

    static Map<String, Object> todayInsightRequest(Map<String, String> params, boolean preloadModel, boolean cacheOnly) {
        Map<String, Object> request = new HashMap<>();
        request.put("anchor_date", params.getOrDefault("anchor_date", LocalDate.now().toString()));
        request.put("window_days", intParam(params, "window_days", 60));
        request.put("max_issues", intParam(params, "max_issues", 8));
        request.put("max_cards", intParam(params, "max_cards", 12));
        request.put("refresh_policy", params.getOrDefault("refresh_policy", "cache_first"));
        request.put("urgent_importance_threshold", doubleParam(params, "urgent_importance_threshold", 0.9));
        request.put("preload_model", boolParam(params, "preload_model", preloadModel));
        if (cacheOnly) {
            request.put("use_cached", true);
            request.put("force_refresh", false);
            request.put("cache_only", true);
            request.put("save", false);
        } else {
            boolean manualGenerate = boolParam(params, "generate", false)
                    || boolParam(params, "force_refresh", false)
                    || boolParam(params, "save", false);
            request.put("use_cached", boolParam(params, "use_cached", !manualGenerate));
            request.put("force_refresh", boolParam(params, "force_refresh", manualGenerate));
            request.put("cache_only", false);
            request.put("save", boolParam(params, "save", true));
        }
        request.put("context", Map.of("update_policy", "daily_0810_kst"));
        return request;
    }

    private static Map<String, Object> emptyDashboardSummary() {
        Map<String, Object> summary = new HashMap<>();
        summary.put("trends", List.of());
        summary.put("articles", List.of());
        summary.put("keywords", List.of());
        summary.put("keywordSearchPoints", List.of());
        summary.put("keywordSeries", List.of());
        summary.put("keywordInsights", List.of());
        summary.put("stockPoints", List.of());
        summary.put("stockRatePoints", List.of());
        summary.put("stockSource", null);
        summary.put("notifications", List.of());
        summary.put("keywordNewsCount", "0");
        summary.put("dartSummary", null);
        return summary;
    }

    private static Map<String, Object> dashboardSummaryStatus(Map<String, Object> summary) {
        boolean hasStock = summary.get("stockSource") != null;
        return Map.of(
                "result_kind", "partial_dashboard_summary",
                "degraded", true,
                "message", "대시보드 summary는 현재 stock chart만 live/fallback으로 채우고 나머지 위젯은 빈 read-model을 반환합니다.",
                "widgets", Map.of(
                        "stock", hasStock ? "available" : "empty",
                        "keyword_trends", "disabled_empty",
                        "trends", "not_wired",
                        "articles", "not_wired",
                        "notifications", "not_wired"
                )
        );
    }

    private static int intParam(Map<String, String> params, String key, int defaultValue) {
        try {
            return Integer.parseInt(params.getOrDefault(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static boolean boolParam(Map<String, String> params, String key, boolean defaultValue) {
        String raw = params.get(key);
        return raw == null ? defaultValue : Boolean.parseBoolean(raw);
    }

    private static double doubleParam(Map<String, String> params, String key, double defaultValue) {
        try {
            return Double.parseDouble(params.getOrDefault(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> todayInsightLatestOrStatus(
            LocalDate anchorDate,
            Map<String, Object> statusPayload
    ) {
        return todayInsightReportService.findLatestOnOrBefore(anchorDate)
                .map(latest -> ResponseEntity.ok(ApiResponse.success(latest)))
                .orElseGet(() -> {
                    log.warn("TodayInsight 저장 결과 없음 | anchor={} status_payload={}", anchorDate, statusPayload);
                    return ResponseEntity.ok(ApiResponse.success(statusPayload));
                });
    }

    @SuppressWarnings("unchecked")
    private static boolean isTodayInsightStatusPlaceholder(Map<String, Object> result) {
        Object provenanceValue = result.get("provenance");
        if (!(provenanceValue instanceof Map<?, ?> provenance)) {
            return false;
        }
        Object placeholder = provenance.get("is_status_placeholder");
        Object resultKind = provenance.get("result_kind");
        return Boolean.parseBoolean(String.valueOf(placeholder))
                || "scheduled_pending".equals(String.valueOf(resultKind));
    }

    private static LocalDate parseAnchorDate(Object value) {
        try {
            return LocalDate.parse(String.valueOf(value));
        } catch (Exception ignored) {
            return LocalDate.now();
        }
    }
}
