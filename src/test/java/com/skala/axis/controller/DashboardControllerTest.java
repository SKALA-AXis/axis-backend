package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import com.skala.axis.exception.AiServerException;
import com.skala.axis.security.CronInternalAuth;
import com.skala.axis.service.AiClientService;
import com.skala.axis.service.DashboardKeywordTrendChartService;
import com.skala.axis.service.DashboardStockChartService;
import com.skala.axis.service.TodayInsightReportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardControllerTest {
    @Mock
    private DashboardStockChartService dashboardStockChartService;

    @Mock
    private DashboardKeywordTrendChartService dashboardKeywordTrendChartService;

    @Mock
    private AiClientService aiClientService;

    @Mock
    private CronInternalAuth cronInternalAuth;

    @Mock
    private TodayInsightReportService todayInsightReportService;

    @InjectMocks
    private DashboardController controller;

    /**
     * generated_fallback(LLM 호출 실패 — OpenAI 한도 소진·rate limit 등)은 진짜 장애 → 502 전파.
     * cron 실패가 곧 알림 역할 (2026-06-16 OpenAI 한도 소진을 이 경로로 포착함).
     */
    @Test
    void cronGeneratePropagatesGeneratedFallbackForVisibility() {
        when(cronInternalAuth.isAuthorized(anyString())).thenReturn(true);
        when(aiClientService.generateTodayInsight(any())).thenReturn(Mono.error(
                new AiServerException("TODAY_INSIGHT_GENERATED_FALLBACK", "호출에 실패했다", HttpStatus.BAD_GATEWAY)));

        ResponseEntity<ApiResponse<Map<String, Object>>> response =
                controller.cronGenerateTodayInsight("Bearer cron-token");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
    }

    /** 신호 없는 아침(no_current_signals)만 cron 을 실패시키지 않는다(200). */
    @Test
    void cronGenerateTreatsNoCurrentSignalsAsSuccess() {
        when(cronInternalAuth.isAuthorized(anyString())).thenReturn(true);
        when(aiClientService.generateTodayInsight(any())).thenReturn(Mono.error(
                new AiServerException("TODAY_INSIGHT_NO_CURRENT_SIGNALS", "호출에 실패했다", HttpStatus.BAD_GATEWAY)));

        ResponseEntity<ApiResponse<Map<String, Object>>> response =
                controller.cronGenerateTodayInsight("Bearer cron-token");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData()).containsEntry("status", "soft_fallback");
    }

    /** 진짜 장애(빈 응답 등)는 그대로 전파 — cron 이 실패하여 가시화되어야 한다. */
    @Test
    void cronGeneratePropagatesGenuineFailure() {
        when(cronInternalAuth.isAuthorized(anyString())).thenReturn(true);
        when(aiClientService.generateTodayInsight(any())).thenReturn(Mono.error(
                new AiServerException("TODAY_INSIGHT_AI_EMPTY_RESPONSE", "axis-ai 응답이 비어 있습니다.",
                        HttpStatus.BAD_GATEWAY)));

        ResponseEntity<ApiResponse<Map<String, Object>>> response =
                controller.cronGenerateTodayInsight("Bearer cron-token");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
    }

    /** 인증 실패는 변동 없음(handler 진입 전 차단). */
    @Test
    void cronGenerateRejectsUnauthorized() {
        when(cronInternalAuth.isAuthorized(any())).thenReturn(false);

        ResponseEntity<ApiResponse<Map<String, Object>>> response =
                controller.cronGenerateTodayInsight(null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
