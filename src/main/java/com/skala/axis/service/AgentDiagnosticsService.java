/*
 * 작성일: 2026-06-05
 * 작성자: 박진
 * 변경이력:
 *   2026-06-05 박진 — 에이전트 진단 Swagger 엔드포인트 추가
 *   2026-06-17 최종민 — 순수 read JDBC 서비스에 @Transactional(readOnly=true) 명시
 */
package com.skala.axis.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentDiagnosticsService {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Object>> LIST_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public Map<String, Object> resultTypes() {
        List<Map<String, Object>> types = Arrays.stream(ResultType.values())
                .map(type -> mapOf(
                        "type", type.apiName,
                        "table", type.tableName,
                        "description", type.description
                ))
                .toList();
        return mapOf(
                "types", types,
                "default_type", "all",
                "note", "local/dev 에이전트 결과 확인용 read model 목록입니다."
        );
    }

    public Map<String, Object> listResults(String rawType, Integer rawLimit, Integer rawOffset) {
        String type = normalizeType(rawType);
        int limit = clamp(rawLimit == null ? DEFAULT_LIMIT : rawLimit, 1, MAX_LIMIT);
        int offset = Math.max(0, rawOffset == null ? 0 : rawOffset);

        if ("all".equals(type)) {
            Map<String, Object> sections = new LinkedHashMap<>();
            for (ResultType resultType : ResultType.values()) {
                sections.put(resultType.apiName, listSingleType(resultType, limit, offset));
            }
            return mapOf(
                    "type", "all",
                    "limit", limit,
                    "offset", offset,
                    "results", sections
            );
        }

        return listSingleType(ResultType.from(type), limit, offset);
    }

    public Map<String, Object> resultDetail(String rawType, String id) {
        ResultType type = ResultType.from(normalizeType(rawType));
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id는 필수입니다.");
        }

        try {
            String sql = type.hasSourceAnalysisId
                    ? "SELECT to_jsonb(t)::text AS row_json FROM " + type.tableName
                    + " t WHERE t.id::text = ? OR t.source_analysis_id = ? LIMIT 1"
                    : "SELECT to_jsonb(t)::text AS row_json FROM " + type.tableName
                    + " t WHERE t.id::text = ? LIMIT 1";
            Object[] args = type.hasSourceAnalysisId ? new Object[] {id, id} : new Object[] {id};
            List<Map<String, Object>> rows = jdbcTemplate.query(
                    sql,
                    (rs, rowNum) -> parseObject(rs.getString("row_json")),
                    args
            );
            return mapOf(
                    "type", type.apiName,
                    "table", type.tableName,
                    "available", true,
                    "id", id,
                    "result", rows.isEmpty() ? null : rows.get(0)
            );
        } catch (DataAccessException e) {
            return unavailable(type, e);
        }
    }

    private Map<String, Object> listSingleType(ResultType type, int limit, int offset) {
        try {
            Integer total = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + type.tableName,
                    Integer.class
            );
            List<Map<String, Object>> items = switch (type) {
                case MIXER -> listMixer(limit, offset);
                case INSIGHT -> listInsight(limit, offset);
                case GLOBAL_TRENDS -> listGlobalTrends(limit, offset);
                case BRIEFING -> listBriefings(limit, offset);
                case INTEGRATED_ISSUE -> listIntegratedIssues(limit, offset);
            };
            return mapOf(
                    "type", type.apiName,
                    "table", type.tableName,
                    "available", true,
                    "total", total == null ? 0 : total,
                    "limit", limit,
                    "offset", offset,
                    "items", items
            );
        } catch (DataAccessException e) {
            return unavailable(type, e);
        }
    }

    private List<Map<String, Object>> listMixer(int limit, int offset) {
        return jdbcTemplate.query(
                """
                    SELECT
                        id::text AS id,
                        source_analysis_id,
                        title,
                        final_one_liner,
                        sk_ax_implication,
                        confidence::double precision AS confidence,
                        array_to_json(input_peer_ids)::text AS peer_ids_json,
                        array_to_json(input_card_ids)::text AS source_ids_json,
                        created_at::text AS created_at,
                        updated_at::text AS updated_at
                    FROM mixer_results
                    ORDER BY created_at DESC
                    LIMIT ? OFFSET ?
                """,
                (rs, rowNum) -> compactAnalysisRow(rs, "mixer"),
                limit,
                offset
        );
    }

    private List<Map<String, Object>> listInsight(int limit, int offset) {
        return jdbcTemplate.query(
                """
                    SELECT
                        id::text AS id,
                        source_analysis_id,
                        title,
                        final_one_liner,
                        sk_ax_implication,
                        confidence::double precision AS confidence,
                        array_to_json(focus_peer_ids)::text AS peer_ids_json,
                        array_to_json(source_card_ids)::text AS source_ids_json,
                        created_at::text AS created_at,
                        updated_at::text AS updated_at
                    FROM insight_reports
                    ORDER BY created_at DESC
                    LIMIT ? OFFSET ?
                """,
                (rs, rowNum) -> compactAnalysisRow(rs, "insight"),
                limit,
                offset
        );
    }

    private List<Map<String, Object>> listGlobalTrends(int limit, int offset) {
        return jdbcTemplate.query(
                """
                    SELECT
                        id::text AS id,
                        source_analysis_id,
                        title,
                        summary AS final_one_liner,
                        sk_ax_implication,
                        confidence::double precision AS confidence,
                        array_to_json(related_peer_ids)::text AS peer_ids_json,
                        array_to_json(related_card_ids)::text AS source_ids_json,
                        industry,
                        region,
                        keyword,
                        keyword_category,
                        trend_date::text AS trend_date,
                        created_at::text AS created_at,
                        updated_at::text AS updated_at
                    FROM global_industry_trends
                    ORDER BY trend_date DESC, created_at DESC
                    LIMIT ? OFFSET ?
                """,
                (rs, rowNum) -> {
                    Map<String, Object> row = compactAnalysisRow(rs, "global_trends");
                    row.put("industry", rs.getString("industry"));
                    row.put("region", rs.getString("region"));
                    row.put("keyword", rs.getString("keyword"));
                    row.put("keyword_category", rs.getString("keyword_category"));
                    row.put("trend_date", rs.getString("trend_date"));
                    return row;
                },
                limit,
                offset
        );
    }

    private List<Map<String, Object>> listBriefings(int limit, int offset) {
        try {
            return jdbcTemplate.query(
                    """
                        SELECT
                            id,
                            NULL AS source_analysis_id,
                            title,
                            COALESCE(NULLIF(key_summary, ''), title) AS final_one_liner,
                            sk_implication AS sk_ax_implication,
                            confidence::double precision AS confidence,
                            '[]'::json AS peer_ids_json,
                            array_to_json(related_card_ids)::text AS source_ids_json,
                            briefing_type,
                            status,
                            created_at::text AS created_at,
                            completed_at::text AS updated_at
                        FROM briefing_reports
                        ORDER BY created_at DESC
                        LIMIT ? OFFSET ?
                    """,
                    (rs, rowNum) -> {
                        Map<String, Object> row = compactAnalysisRow(rs, "briefing");
                        row.put("briefing_type", rs.getString("briefing_type"));
                        row.put("status", rs.getString("status"));
                        return row;
                    },
                    limit,
                    offset
            );
        } catch (BadSqlGrammarException ignored) {
            return jdbcTemplate.query(
                    """
                        SELECT
                            id,
                            NULL AS source_analysis_id,
                            title,
                            title AS final_one_liner,
                            NULL AS sk_ax_implication,
                            confidence::double precision AS confidence,
                            '[]'::json AS peer_ids_json,
                            '[]'::json AS source_ids_json,
                            briefing_type,
                            status,
                            created_at::text AS created_at,
                            completed_at::text AS updated_at
                        FROM briefing_reports
                        ORDER BY created_at DESC
                        LIMIT ? OFFSET ?
                    """,
                    (rs, rowNum) -> {
                        Map<String, Object> row = compactAnalysisRow(rs, "briefing");
                        row.put("briefing_type", rs.getString("briefing_type"));
                        row.put("status", rs.getString("status"));
                        return row;
                    },
                    limit,
                    offset
            );
        }
    }

    private List<Map<String, Object>> listIntegratedIssues(int limit, int offset) {
        return jdbcTemplate.query(
                """
                    SELECT
                        id::text AS id,
                        issue_key,
                        headline AS title,
                        one_line_summary AS final_one_liner,
                        main_company,
                        event_type,
                        confidence::double precision AS confidence,
                        is_valid,
                        status,
                        array_to_json(mentioned_peer_companies)::text AS peer_ids_json,
                        array_to_json(source_ids)::text AS source_ids_json,
                        created_at::text AS created_at,
                        updated_at::text AS updated_at
                    FROM integrated_issues
                    ORDER BY created_at DESC
                    LIMIT ? OFFSET ?
                """,
                (rs, rowNum) -> {
                    Map<String, Object> row = mapOf(
                            "type", "integrated_issue",
                            "id", rs.getString("id"),
                            "source_analysis_id", rs.getString("issue_key"),
                            "title", rs.getString("title"),
                            "final_one_liner", rs.getString("final_one_liner"),
                            "confidence", nullableDouble(rs, "confidence"),
                            "peer_ids", parseList(rs.getString("peer_ids_json")),
                            "source_ids", parseList(rs.getString("source_ids_json")),
                            "created_at", rs.getString("created_at"),
                            "updated_at", rs.getString("updated_at")
                    );
                    row.put("main_company", rs.getString("main_company"));
                    row.put("event_type", rs.getString("event_type"));
                    row.put("is_valid", rs.getBoolean("is_valid"));
                    row.put("status", rs.getString("status"));
                    return row;
                },
                limit,
                offset
        );
    }

    private Map<String, Object> compactAnalysisRow(ResultSet rs, String type) throws SQLException {
        return mapOf(
                "type", type,
                "id", rs.getString("id"),
                "source_analysis_id", rs.getString("source_analysis_id"),
                "title", rs.getString("title"),
                "final_one_liner", rs.getString("final_one_liner"),
                "sk_ax_implication", rs.getString("sk_ax_implication"),
                "confidence", nullableDouble(rs, "confidence"),
                "peer_ids", parseList(rs.getString("peer_ids_json")),
                "source_ids", parseList(rs.getString("source_ids_json")),
                "created_at", rs.getString("created_at"),
                "updated_at", rs.getString("updated_at")
        );
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
            return mapOf("raw", json);
        }
    }

    private Map<String, Object> unavailable(ResultType type, DataAccessException e) {
        return mapOf(
                "type", type.apiName,
                "table", type.tableName,
                "available", false,
                "total", 0,
                "items", List.of(),
                "warning", "테이블 또는 컬럼이 현재 DB 스키마에 없습니다.",
                "error", e.getMostSpecificCause().getMessage()
        );
    }

    private static String normalizeType(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return "all";
        }
        return rawType.trim().toLowerCase(Locale.ROOT).replace("-", "_");
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static Map<String, Object> mapOf(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            map.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return map;
    }

    private enum ResultType {
        MIXER("mixer", "mixer_results", true, "MixerAnalysisAgent 결과"),
        INSIGHT("insight", "insight_reports", true, "InsightCascadeAgent 결과"),
        GLOBAL_TRENDS("global_trends", "global_industry_trends", true, "GlobalTrendsAgent 저장 결과"),
        BRIEFING("briefing", "briefing_reports", false, "BriefingGenerationAgent 결과"),
        INTEGRATED_ISSUE("integrated_issue", "integrated_issues", false, "IssueIntegrationAgent canonical issue 결과");

        private final String apiName;
        private final String tableName;
        private final boolean hasSourceAnalysisId;
        private final String description;

        ResultType(String apiName, String tableName, boolean hasSourceAnalysisId, String description) {
            this.apiName = apiName;
            this.tableName = tableName;
            this.hasSourceAnalysisId = hasSourceAnalysisId;
            this.description = description;
        }

        private static ResultType from(String type) {
            return Arrays.stream(values())
                    .filter(value -> value.apiName.equals(type))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("지원하지 않는 결과 type입니다: " + type));
        }
    }
}
