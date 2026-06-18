/*
 * 작성일: 2026-06-09
 * 작성자: 최종민
 * 변경이력:
 *   2026-06-09 최종민 — K8s CronJob용 today-insight cron-generate 추가
 */
package com.skala.axis.service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

public final class TodayInsightCronRequestFactory {
    private TodayInsightCronRequestFactory() {
    }

    /** 평일 08:10 KST 사전 생성 — cache miss 방지용 force refresh + DB save. */
    public static Map<String, Object> dailyGenerateRequest(LocalDate anchorDate) {
        Map<String, Object> request = new HashMap<>();
        request.put("anchor_date", anchorDate.toString());
        request.put("window_days", 60);
        request.put("max_issues", 8);
        request.put("max_cards", 12);
        request.put("use_cached", false);
        request.put("force_refresh", true);
        request.put("refresh_policy", "cache_first");
        request.put("urgent_importance_threshold", 0.9);
        request.put("cache_only", false);
        request.put("preload_model", true);
        request.put("save", true);
        request.put("context", Map.of("update_policy", "daily_0810_kst"));
        return request;
    }
}
