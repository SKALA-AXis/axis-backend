package com.skala.axis.config;

import com.skala.axis.service.AiClientService;
import com.skala.axis.service.BriefingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class SchedulerConfig {
    private final AiClientService aiClientService;
    private final BriefingService briefingService;

    @Scheduled(cron = "0 0 * * * *")
    public void triggerIngestionPipeline() {
        log.info("수집 파이프라인 트리거");
        aiClientService.triggerPipeline(List.of("samsung_sds", "lg_cns"))
                .subscribe(null, e -> log.error("파이프라인 트리거 실패: {}", e.getMessage()));
    }

    @Scheduled(cron = "0 30 8 * * MON-FRI")
    public void sendDailyBriefing() {
        log.info("일일 브리핑 전송 시작");
        briefingService.generateAndSend();
    }
}
