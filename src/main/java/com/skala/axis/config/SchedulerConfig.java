/*
 * 작성일: 2026-04-21
 * 작성자: 최종민
 * 변경이력:
 *   2026-04-21 최종민 — axis-backend 베이스라인 생성, 이후 파이프라인 수동 브리핑 트리거·KST 타임존 수정, today-insight 크론 생성, 대형 이벤트 이메일 알림 추가
 *   2026-05-06 박진 — 백엔드 초안에 반영, 이후 투데이 인사이트 API와 일일 생성 저장 연결
 *   2026-05-12 박지원 — modified 추적 처리
 */
package com.skala.axis.config;

import com.skala.axis.service.AiClientService;
import com.skala.axis.service.BriefingService;
import com.skala.axis.service.EventAlertService;
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
    private final EventAlertService eventAlertService;

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

    /**
     * 대형 이벤트(수주·파트너십·M&A) 1회성 알림 자동 스캔.
     * 기본 매시 :15(07–22시) — 노드 야간 셧다운(23–07 KST) 창을 피한다.
     */
    @Scheduled(cron = "${axis.alert.scan-cron:0 15 7-22 * * *}", zone = "Asia/Seoul")
    public void scanEventAlerts() {
        EventAlertService.ScanResult result = eventAlertService.scanRecent("auto");
        log.info("대형 이벤트 알림 자동 스캔 완료 — 후보 {} 발송 {} 스킵 {} 실패 {}",
                result.candidates(), result.sent(), result.skipped(), result.failed());
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
