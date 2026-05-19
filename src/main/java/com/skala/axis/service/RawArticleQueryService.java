package com.skala.axis.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RawArticleQueryService {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ApiContractFixtureService fixture;

    public List<Map<String, Object>> rawArticles() {
        return rawArticleItems(Map.of("limit", "100", "offset", "0"));
    }

    public Map<String, Object> rawArticleList(Map<String, String> params) {
        int limit = clamp(intValue(params, "limit", 30), 1, 200);
        int offset = Math.max(0, intValue(params, "offset", 0));

        try {
            QueryParts query = buildWhere(params);
            Integer total = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM raw_articles " + query.whereSql(),
                    Integer.class,
                    query.args().toArray()
            );

            List<Object> dataArgs = new ArrayList<>(query.args());
            dataArgs.add(limit);
            dataArgs.add(offset);
            List<Map<String, Object>> items = jdbcTemplate.query(
                    """
                        SELECT
                            id,
                            title,
                            url,
                            source_type,
                            source_name,
                            publisher,
                            company::text AS company_json,
                            published_at,
                            collected_at,
                            importance_level,
                            importance_score,
                            processing_status,
                            relevance_label
                        FROM raw_articles
                        %s
                        ORDER BY collected_at DESC NULLS LAST, id DESC
                        LIMIT ? OFFSET ?
                    """.formatted(query.whereSql()),
                    (rs, rowNum) -> mapListRow(rs),
                    dataArgs.toArray()
            );

            return mapOf(
                    "items", items,
                    "total", total == null ? 0 : total,
                    "limit", limit,
                    "offset", offset
            );
        } catch (BadSqlGrammarException ignored) {
            return fixture.rawArticleList(params);
        }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> rawArticleItems(Map<String, String> params) {
        return (List<Map<String, Object>>) rawArticleList(params).get("items");
    }

    public Map<String, Object> rawArticleDetail(long id) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.query(
                    """
                        SELECT
                            ra.id,
                            ra.title,
                            ra.content,
                            ra.url,
                            ra.source_type,
                            ra.source_name,
                            ra.publisher,
                            ra.company::text AS company_json,
                            ra.published_at,
                            ra.collected_at,
                            ra.importance_level,
                            ra.importance_score,
                            ra.processing_status,
                            ra.relevance_label,
                            ra.relevance_reason,
                            COALESCE(mu.metadata, '{}'::jsonb)::text AS metadata_json
                        FROM raw_articles ra
                        LEFT JOIN raw_article_metadata_unified mu
                            ON mu.raw_article_id = ra.id
                        WHERE ra.id = ?
                        LIMIT 1
                    """,
                    (rs, rowNum) -> {
                        Map<String, Object> row = mapListRow(rs);
                        row.put("content", rs.getString("content"));
                        row.put("relevanceReason", rs.getString("relevance_reason"));
                        row.put("metadata", parseObject(rs.getString("metadata_json")));
                        row.put("rawTextAvailable", rs.getString("content") != null);
                        return row;
                    },
                    id
            );

            return rows.isEmpty() ? Map.of() : rows.get(0);
        } catch (BadSqlGrammarException ignored) {
            return fixture.rawArticleDetail((int) id);
        }
    }

    private QueryParts buildWhere(Map<String, String> params) {
        List<String> clauses = new ArrayList<>();
        List<Object> args = new ArrayList<>();

        String peerId = blankToNull(params.get("peer_id"));
        if (peerId != null) {
            clauses.add("company @> CAST(? AS jsonb)");
            args.add("[\"" + peerId.replace("\"", "\\\"") + "\"]");
        }

        String importanceLevel = blankToNull(params.get("importance_level"));
        if (importanceLevel != null) {
            clauses.add("importance_level = ?");
            args.add(importanceLevel);
        }

        String processingStatus = blankToNull(params.get("processing_status"));
        if (processingStatus != null) {
            clauses.add("processing_status = ?");
            args.add(processingStatus);
        }

        String sourceType = blankToNull(params.get("source_type"));
        if (sourceType != null) {
            clauses.add("source_type = ?");
            args.add(sourceType);
        }

        String query = blankToNull(params.get("q"));
        if (query != null) {
            clauses.add("(title ILIKE ? OR content ILIKE ? OR source_name ILIKE ?)");
            String pattern = "%" + query + "%";
            args.add(pattern);
            args.add(pattern);
            args.add(pattern);
        }

        return new QueryParts(
                clauses.isEmpty() ? "" : "WHERE " + String.join(" AND ", clauses),
                args
        );
    }

    private Map<String, Object> mapListRow(ResultSet rs) throws SQLException {
        List<String> companies = parseStringList(rs.getString("company_json"));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("title", rs.getString("title"));
        row.put("url", rs.getString("url"));
        row.put("sourceType", rs.getString("source_type"));
        row.put("sourceName", rs.getString("source_name"));
        row.put("publisher", rs.getString("publisher"));
        row.put("peerId", companies.isEmpty() ? null : companies.get(0));
        row.put("companies", companies);
        row.put("publishedAt", iso(rs, "published_at"));
        row.put("collectedAt", iso(rs, "collected_at"));
        row.put("importanceLevel", rs.getString("importance_level"));
        row.put("importanceScore", rs.getObject("importance_score"));
        row.put("processingStatus", rs.getString("processing_status"));
        row.put("relevanceLabel", rs.getString("relevance_label"));
        return row;
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private Map<String, Object> parseObject(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private String iso(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toString();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private int intValue(Map<String, String> params, String key, int defaultValue) {
        try {
            return Integer.parseInt(params.getOrDefault(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            map.put(String.valueOf(values[i]), values[i + 1]);
        }
        return map;
    }

    private record QueryParts(String whereSql, List<Object> args) {
    }
}
