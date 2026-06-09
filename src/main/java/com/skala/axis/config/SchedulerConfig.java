package com.skala.axis.config;

import com.skala.axis.service.AiClientService;
import com.skala.axis.service.BriefingService;
import com.skala.axis.service.TodayInsightCronRequestFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
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
        Map<String, Object> request = TodayInsightCronRequestFactory.dailyGenerateRequest(LocalDate.now());

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
