package com.skala.axis.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.skala.axis.query.GlobalTrendsQueries.COUNT_BY_DATE_RANGE;
import static com.skala.axis.query.GlobalTrendsQueries.LATEST_TREND_DATE;
import static com.skala.axis.query.GlobalTrendsQueries.LIST_BY_DATE_RANGE;

@Slf4j
@Service
@RequiredArgsConstructor
public class GlobalTrendsService {
    private static final TypeReference<List<Object>> LIST_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public Map<String, Object> listTrends(String from, String to, int limit, int offset) {
        int safeLimit = clamp(limit, 1, 50);
        int safeOffset = Math.max(0, offset);
        LocalDate fromDate = parseDate(from, LocalDate.now().minusDays(30));
        LocalDate toDate = parseDate(to, LocalDate.now());

        try {
            Integer total = jdbcTemplate.queryForObject(
                    COUNT_BY_DATE_RANGE,
                    Integer.class,
                    fromDate,
                    toDate
            );
            List<Map<String, Object>> items = jdbcTemplate.query(
                    LIST_BY_DATE_RANGE,
                    (rs, rowNum) -> mapTrendRow(rs),
                    fromDate,
                    toDate,
                    safeLimit,
                    safeOffset
            );
            List<String> latestDates = jdbcTemplate.query(
                    LATEST_TREND_DATE,
                    (rs, rowNum) -> rs.getString(1)
            );
            String latestTrendDate = latestDates.isEmpty() ? "" : latestDates.get(0);

            return Map.of(
                    "items", items,
                    "total", total == null ? 0 : total,
                    "limit", safeLimit,
                    "offset", safeOffset,
                    "from", fromDate.toString(),
                    "to", toDate.toString(),
                    "latest_trend_date", latestTrendDate == null ? "" : latestTrendDate
            );
        } catch (DataAccessException error) {
            log.warn("Global trends list query failed: {}", error.getMostSpecificCause().getMessage());
            return Map.of(
                    "items", List.of(),
                    "total", 0,
                    "limit", safeLimit,
                    "offset", safeOffset,
                    "from", fromDate.toString(),
                    "to", toDate.toString(),
                    "latest_trend_date", "",
                    "warning", "global_industry_trends 조회 실패"
            );
        }
    }

    private Map<String, Object> mapTrendRow(ResultSet rs) throws SQLException {
        Map<String, Object> payload = parseObject(rs.getString("payload_json"));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getString("id"));
        row.put("source_analysis_id", rs.getString("source_analysis_id"));
        row.put("trend_date", rs.getString("trend_date"));
        row.put("industry", rs.getString("industry"));
        row.put("region", rs.getString("region"));
        row.put("keyword", rs.getString("keyword"));
        row.put("keyword_category", rs.getString("keyword_category"));
        row.put("title", rs.getString("title"));
        row.put("summary", rs.getString("summary"));
        row.put("mention_count", rs.getInt("mention_count"));
        row.put("impact_score", nullableDouble(rs, "impact_score"));
        row.put("confidence", nullableDouble(rs, "confidence"));
        row.put("related_peer_ids", parseList(rs.getString("peer_ids_json")));
        row.put("related_card_ids", parseList(rs.getString("card_ids_json")));
        row.put("sk_ax_implication", rs.getString("sk_ax_implication"));
        row.put("created_at", rs.getString("created_at"));
        row.put("updated_at", rs.getString("updated_at"));
        row.put("peer_alignment", payload.getOrDefault("peer_alignment", List.of()));
        row.put("impact_matrix", payload.getOrDefault("impact_matrix", List.of()));
        row.put("forecasts", payload.getOrDefault("forecasts", List.of()));
        row.put("final_one_liner", payload.getOrDefault("final_one_liner", ""));
        row.put("overall_summary", payload.getOrDefault("overall_summary", ""));
        row.put("company_movements", payload.getOrDefault("company_movements", List.of()));
        row.put("leading_companies", payload.getOrDefault("leading_companies", List.of()));
        row.put("evidence_source_links", payload.getOrDefault("evidence_source_links", List.of()));
        row.put("intensity", payload.getOrDefault("intensity", ""));
        row.put("frequency_delta_pct", payload.getOrDefault("frequency_delta_pct", 0));
        return row;
    }

    private LocalDate parseDate(String raw, LocalDate fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private List<Object> parseList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, LIST_TYPE);
        } catch (Exception ignored) {
            return List.of(json);
        }
    }

    private Map<String, Object> parseObject(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception ignored) {
            return Map.of("raw", json);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
