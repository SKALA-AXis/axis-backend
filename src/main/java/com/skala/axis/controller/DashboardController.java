package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import com.skala.axis.exception.AiServerException;
import com.skala.axis.security.CronInternalAuth;
import com.skala.axis.service.AiClientService;
import com.skala.axis.service.ApiContractFixtureService;
import com.skala.axis.service.DashboardKeywordTrendChartService;
import com.skala.axis.service.DashboardStockChartService;
import com.skala.axis.service.TodayInsightCronRequestFactory;
import com.skala.axis.service.TodayInsightReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {
    private final ApiContractFixtureService fixture;
    private final DashboardStockChartService dashboardStockChartService;
    private final DashboardKeywordTrendChartService dashboardKeywordTrendChartService;
    private final AiClientService aiClientService;
    private final CronInternalAuth cronInternalAuth;
    private final TodayInsightReportService todayInsightReportService;
    private final AtomicBoolean todayInsightWarmupInFlight = new AtomicBoolean(false);

    @Value("${axis.fixtures.enabled:false}")
    private boolean fixturesEnabled;

    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDashboardSummary(@RequestParam Map<String, String> params) {
        Map<String, Object> dashboardSummary = fixture.frontendDashboard();
        dashboardStockChartService.applyDailyRateChart(dashboardSummary);
        dashboardKeywordTrendChartService.removeKeywordTrendChart(dashboardSummary);
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

        try {
            Map<String, Object> result = aiClientService.generateTodayInsight(request).block();
            if (result != null && !result.isEmpty()) {
                if (isTodayInsightStatusPlaceholder(result)) {
                    return todayInsightLatestOrStatus(anchorDate, result);
                }
                return ResponseEntity.ok(ApiResponse.success(result));
            }
            log.warn("TodayInsight axis-ai 응답 비어 있음");
            return todayInsightLatestOrUnavailable(anchorDate, "axis-ai returned empty response");
        } catch (AiServerException e) {
            log.warn("TodayInsight axis-ai 호출 실패 | {}", e.getMessage());
            return todayInsightLatestOrUnavailable(anchorDate, e.getMessage());
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
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(ApiResponse.success(Map.of(
                            "status", "failed",
                            "anchor_date", request.get("anchor_date"),
                            "error", e.getMessage()
                    )));
        } catch (Exception e) {
            log.error("TodayInsight cron-generate 실패 | anchor={} error={}",
                    request.get("anchor_date"), e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.success(Map.of(
                            "status", "failed",
                            "anchor_date", request.get("anchor_date"),
                            "error", e.getMessage()
                    )));
        }
    }

    @PostMapping("/today-insight/warmup")
    public ResponseEntity<ApiResponse<Map<String, Object>>> warmupTodayInsight(@RequestParam Map<String, String> params) {
        Map<String, Object> request = todayInsightRequest(params, true, true);
        if (!todayInsightWarmupInFlight.compareAndSet(false, true)) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(Map.of(
                    "status", "already_running",
                    "anchor_date", request.get("anchor_date"),
                    "cache_only", request.get("cache_only"),
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
            request.put("use_cached", boolParam(params, "use_cached", true));
            request.put("force_refresh", boolParam(params, "force_refresh", false));
            request.put("cache_only", false);
            request.put("save", boolParam(params, "save", true));
        }
        request.put("context", Map.of("update_policy", "daily_0810_kst"));
        return request;
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

    private ResponseEntity<ApiResponse<Map<String, Object>>> todayInsightUnavailable(String error) {
        if (fixturesEnabled) {
            log.warn("TodayInsight fixture fallback enabled");
            return ResponseEntity.ok(ApiResponse.success(fixture.todayInsight()));
        }
        String safeError = error == null || error.isBlank() ? "axis-ai unavailable" : error;
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResponse.success(Map.of(
                        "status", "failed",
                        "result_kind", "axis_ai_unavailable",
                        "error", safeError
                )));
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> todayInsightLatestOrStatus(
            LocalDate anchorDate,
            Map<String, Object> statusPayload
    ) {
        return todayInsightReportService.findLatestOnOrBefore(anchorDate)
                .map(latest -> ResponseEntity.ok(ApiResponse.success(latest)))
                .orElseGet(() -> ResponseEntity.ok(ApiResponse.success(statusPayload)));
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> todayInsightLatestOrUnavailable(
            LocalDate anchorDate,
            String error
    ) {
        return todayInsightReportService.findLatestOnOrBefore(anchorDate)
                .map(latest -> ResponseEntity.ok(ApiResponse.success(latest)))
                .orElseGet(() -> todayInsightUnavailable(error));
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
