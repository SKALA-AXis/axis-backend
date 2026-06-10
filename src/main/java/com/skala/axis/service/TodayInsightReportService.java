package com.skala.axis.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TodayInsightReportService {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public Optional<Map<String, Object>> findLatestOnOrBefore(LocalDate anchorDate) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT report_date::text AS report_date,
                           output_payload::text AS output_payload,
                           created_at
                      FROM today_insight_reports
                     WHERE report_date <= CAST(? AS date)
                       AND status = 'active'
                     ORDER BY report_date DESC, created_at DESC
                     LIMIT 1
                    """, anchorDate.toString());
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            return parsePayload(rows.get(0), anchorDate);
        } catch (Exception e) {
            log.debug("TodayInsight latest DB fallback skipped | anchor={} error={}", anchorDate, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<Map<String, Object>> parsePayload(Map<String, Object> row, LocalDate anchorDate) {
        Object rawPayload = row.get("output_payload");
        if (rawPayload == null) {
            return Optional.empty();
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(String.valueOf(rawPayload), MAP_TYPE);
            String reportDate = String.valueOf(row.getOrDefault("report_date", ""));
            payload.putIfAbsent("report_date", reportDate);
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
