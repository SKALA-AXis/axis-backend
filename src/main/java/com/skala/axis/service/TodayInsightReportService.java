/*
 * 작성일: 2026-06-10
 * 작성자: 박진
 * 변경이력:
 *   2026-06-10 박진 — mixer 결과 영속화 및 최신 인사이트 제공
 *   2026-06-14 안가은 — briefing/dashboard read model 갱신 및 placeholder 대신 최신 리포트 반환
 *   2026-06-17 최종민 — 순수 read JDBC 서비스에 @Transactional(readOnly=true) 명시
 */
package com.skala.axis.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.skala.axis.query.TodayInsightReportQueries.LATEST_ON_OR_BEFORE_SQL;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TodayInsightReportService {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final int LOOKBACK_REPORT_LIMIT = 14;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public Optional<Map<String, Object>> findLatestOnOrBefore(LocalDate anchorDate) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    LATEST_ON_OR_BEFORE_SQL,
                    anchorDate.toString(),
                    LOOKBACK_REPORT_LIMIT
            );
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            Optional<Map<String, Object>> firstPlaceholder = Optional.empty();
            for (Map<String, Object> row : rows) {
                Optional<Map<String, Object>> parsed = parsePayload(row, anchorDate);
                if (parsed.isEmpty()) {
                    continue;
                }
                if (isStatusPlaceholder(parsed.get())) {
                    if (firstPlaceholder.isEmpty()) {
                        firstPlaceholder = parsed;
                    }
                    continue;
                }
                return parsed;
            }
            return firstPlaceholder;
        } catch (Exception e) {
            log.debug("TodayInsight latest DB fallback skipped | anchor={} error={}", anchorDate, e.getMessage());
            return Optional.empty();
        }
    }

    private boolean isStatusPlaceholder(Map<String, Object> payload) {
        Object rawProvenance = payload.get("provenance");
        if (!(rawProvenance instanceof Map<?, ?> provenance)) {
            return false;
        }
        Object placeholder = provenance.get("is_status_placeholder");
        String resultKind = String.valueOf(provenance.get("result_kind"));
        return Boolean.parseBoolean(String.valueOf(placeholder))
                || "no_current_signals".equals(resultKind)
                || "scheduled_pending".equals(resultKind);
    }

    private Optional<Map<String, Object>> parsePayload(Map<String, Object> row, LocalDate anchorDate) {
        Object rawPayload = row.get("output_payload");
        if (rawPayload == null) {
            return Optional.empty();
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(String.valueOf(rawPayload), MAP_TYPE);
            String reportDate = String.valueOf(row.getOrDefault("report_date", ""));
            Object createdAt = row.get("created_at");
            payload.putIfAbsent("report_date", reportDate);
            if (createdAt != null) {
                payload.put("data_updated_at", createdAt.toString());
            }
            payload.put("served_anchor_date", anchorDate.toString());

            Map<String, Object> provenance = new HashMap<>();
            Object rawProvenance = payload.get("provenance");
            if (rawProvenance instanceof Map<?, ?> rawMap) {
                rawMap.forEach((key, value) -> provenance.put(String.valueOf(key), value));
            }
            provenance.put("cache_lookup", "backend_latest_saved_on_or_before_anchor");
            provenance.put("served_anchor_date", anchorDate.toString());
            provenance.put("cached_report_date", reportDate);
            if (!reportDate.isBlank() && !reportDate.equals(anchorDate.toString())) {
                provenance.put("latest_fallback", true);
            }
            payload.put("provenance", provenance);
            return Optional.of(payload);
        } catch (Exception e) {
            log.debug("TodayInsight latest DB fallback payload parse failed | error={}", e.getMessage());
            return Optional.empty();
        }
    }
}
