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

import static com.skala.axis.query.RawArticleQueries.COUNT_BY_FILTER;
import static com.skala.axis.query.RawArticleQueries.DETAIL_BY_ID;
import static com.skala.axis.query.RawArticleQueries.LIST_BY_FILTER;

@Service
@RequiredArgsConstructor
public class RawArticleQueryService {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public List<Map<String, Object>> rawArticles() {
        return rawArticleItems(Map.of("limit", "100", "offset", "0"));
    }

    public Map<String, Object> rawArticleList(Map<String, String> params) {
        int limit = clamp(intValue(params, "limit", 30), 1, 200);
        int offset = Math.max(0, intValue(params, "offset", 0));

        try {
            QueryParts query = buildWhere(params);
            Integer total = jdbcTemplate.queryForObject(
                    COUNT_BY_FILTER.formatted(query.whereSql()),
                    Integer.class,
                    query.args().toArray()
            );

            List<Object> dataArgs = new ArrayList<>(query.args());
            dataArgs.add(limit);
            dataArgs.add(offset);
            List<Map<String, Object>> items = jdbcTemplate.query(
                    LIST_BY_FILTER.formatted(query.whereSql()),
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
            return mapOf(
                    "items", List.of(),
                    "total", 0,
                    "limit", limit,
                    "offset", offset
            );
        }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> rawArticleItems(Map<String, String> params) {
        return (List<Map<String, Object>>) rawArticleList(params).get("items");
    }

    public Map<String, Object> rawArticleDetail(long id) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.query(
                    DETAIL_BY_ID,
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
            return Map.of();
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
