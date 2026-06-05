package com.skala.axis.config;

import com.skala.axis.service.AiClientService;
import com.skala.axis.service.BriefingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@ConditionalOnProperty(name = "axis.scheduler.enabled", havingValue = "true")
@RequiredArgsConstructor
public class SchedulerConfig {
    private final AiClientService aiClientService;
    private final BriefingService briefingService;

    @Value("${axis.scheduler.ingestion-peer-ids}")
    private List<String> ingestionPeerIds;

    @Scheduled(cron = "0 0 * * * *")
    public void triggerIngestionPipeline() {
        log.info("수집 파이프라인 트리거");
        aiClientService.triggerPipeline("A", ingestionPeerIds)
                .subscribe(null, e -> log.error("파이프라인 트리거 실패: {}", e.getMessage()));
    }

    @Scheduled(cron = "0 30 8 * * MON-FRI", zone = "Asia/Seoul")
    public void sendDailyBriefing() {
        log.info("일일 브리핑 전송 시작");
        briefingService.generateAndSend();
    }

    @Scheduled(cron = "${axis.scheduler.today-insight-cron:0 10 8 * * MON-FRI}", zone = "Asia/Seoul")
    public void warmupTodayInsight() {
        Map<String, Object> request = new HashMap<>();
        request.put("anchor_date", LocalDate.now().toString());
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

        log.info("TodayInsight 오전 사전 생성 시작 | anchor={}", request.get("anchor_date"));
        aiClientService.generateTodayInsight(request)
                .subscribe(
                        result -> log.info(
                                "TodayInsight 오전 사전 생성 완료 | anchor={} headline={}",
                                request.get("anchor_date"),
                                result == null ? "" : result.getOrDefault("headline", "")
                        ),
                        e -> log.error("TodayInsight 오전 사전 생성 실패: {}", e.getMessage())
                );
    }
}
