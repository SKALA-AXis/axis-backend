/*
 * 작성일: 2026-06-18
 * 작성자: 안가은
 * 변경이력:
 *   2026-06-18 안가은 — 오늘 인사이트가 placeholder 대신 최신 리포트를 반환하도록 수정한 테스트 작성
 */
package com.skala.axis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TodayInsightReportServiceTest {
    @Mock
    private JdbcTemplate jdbcTemplate;

    private TodayInsightReportService service;

    @BeforeEach
    void setUp() {
        service = new TodayInsightReportService(jdbcTemplate, new ObjectMapper());
    }

    @Test
    void findLatestOnOrBeforeSkipsStatusPlaceholderForLatestRealReport() {
        when(jdbcTemplate.queryForList(anyString(), eq("2026-06-18"), eq(14))).thenReturn(List.of(
                row("2026-06-18", """
                        {
                          "headline": "오늘 상태 placeholder",
                          "provenance": {
                            "result_kind": "no_current_signals",
                            "is_status_placeholder": true
                          }
                        }
                        """),
                row("2026-06-17", """
                        {
                          "headline": "전날 실제 신호",
                          "signals": [{"id": "s1", "value": "주요 신호"}],
                          "provenance": {"result_kind": "generated"}
                        }
                        """)
        ));

        Optional<Map<String, Object>> result =
                service.findLatestOnOrBefore(LocalDate.of(2026, 6, 18));

        assertThat(result).isPresent();
        assertThat(result.get()).containsEntry("headline", "전날 실제 신호");
        assertThat(result.get()).containsEntry("report_date", "2026-06-17");
        assertThat(result.get()).containsEntry("served_anchor_date", "2026-06-18");
        assertThat(result.get().get("provenance")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> provenance = (Map<String, Object>) result.get().get("provenance");
        assertThat(provenance)
                .containsEntry("latest_fallback", true)
                .containsEntry("cached_report_date", "2026-06-17");
    }

    private Map<String, Object> row(String reportDate, String payload) {
        return Map.of(
                "report_date", reportDate,
                "output_payload", payload,
                "created_at", "2026-06-18T08:10:00Z"
        );
    }
}
