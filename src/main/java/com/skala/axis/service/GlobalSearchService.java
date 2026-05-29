package com.skala.axis.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class GlobalSearchService {
    private static final int DEFAULT_LIMIT = 8;
    private static final int MAX_LIMIT = 30;
    private static final List<String> ALL_SCOPES = List.of("BRIEFING", "CARD_NEWS", "KEYWORD_GRAPH", "PEER_PLUS");

    private final JdbcTemplate jdbcTemplate;

    @Transactional(readOnly = true)
    public Map<String, Object> search(Map<String, Object> request) {
        SearchCriteria criteria = SearchCriteria.from(request);
        List<Map<String, Object>> items = new ArrayList<>();

        if (criteria.includes("BRIEFING")) {
            items.addAll(searchBriefings(criteria));
        }
        if (criteria.includes("CARD_NEWS")) {
            items.addAll(searchCardNews(criteria));
        }
        if (criteria.includes("KEYWORD_GRAPH")) {
            items.addAll(searchKeywordGraph(criteria));
        }
        if (criteria.includes("PEER_PLUS")) {
            items.addAll(searchPeers(criteria));
        }

        items.sort((left, right) -> {
            int scoreCompare = Double.compare(doubleValue(right.get("score")), doubleValue(left.get("score")));
            if (scoreCompare != 0) {
                return scoreCompare;
            }
            return stringValue(right.get("date")).compareTo(stringValue(left.get("date")));
        });

        int limit = Math.min(criteria.limit(), items.size());
        List<Map<String, Object>> limitedItems = items.subList(0, limit);
        Map<String, Long> counts = new LinkedHashMap<>();
        ALL_SCOPES.forEach(scope -> counts.put(scope, items.stream().filter(item -> scope.equals(item.get("type"))).count()));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("query", criteria.query());
        response.put("scopes", criteria.scopes());
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("period", criteria.period());
        filters.put("startDate", criteria.startDate());
        filters.put("endDate", criteria.endDate());
        response.put("filters", filters);
        response.put("items", limitedItems);
        response.put("counts", counts);
        response.put("total", items.size());
        response.put("hasMore", items.size() > limitedItems.size());
        return response;
    }

    private List<Map<String, Object>> searchBriefings(SearchCriteria criteria) {
        String searchText = "br.global_search_text";
        String briefingDate = "COALESCE(br.report_date, br.date_to, br.date_from)";
        SqlParts parts = new SqlParts();
        if (criteria.hasQuery()) {
            if (criteria.queryDate() != null) {
                parts.add("(" + searchText + " ILIKE ? OR " + briefingDate + " = ?)",
                        criteria.likePattern(),
                        Date.valueOf(criteria.queryDate()));
            } else {
                parts.add(searchText + " ILIKE ?", criteria.likePattern());
            }
        }
        addDateRange(parts, criteria, briefingDate);
        if (!parts.hasConditions()) {
            parts.add("TRUE");
        }
        String sql = """
                SELECT
                    br.id,
                    br.title,
                    COALESCE(NULLIF(br.key_summary, ''), NULLIF(br.sk_implication, ''), br.period_label, br.briefing_type) AS snippet,
                    COALESCE(br.report_date, br.date_to, br.date_from) AS item_date,
                    br.briefing_type,
                    br.status,
                    CASE
                        WHEN lower(br.title) = lower(?) THEN 120
                        WHEN %s = ? THEN 95
                        WHEN lower(br.title) LIKE lower(?) THEN 90
                        WHEN lower(COALESCE(br.key_summary, '')) LIKE lower(?) THEN 75
                        ELSE 45
                    END AS score
                FROM briefing_reports br
                WHERE %s
                ORDER BY score DESC, COALESCE(br.report_date, br.date_to, br.date_from) DESC, br.created_at DESC
                LIMIT ?
                """.formatted(briefingDate, parts.whereSql());
        List<Object> params = new ArrayList<>();
        params.add(criteria.query());
        params.add(criteria.queryDate() == null ? Date.valueOf(LocalDate.of(1900, 1, 1)) : Date.valueOf(criteria.queryDate()));
        params.add(criteria.likePattern());
        params.add(criteria.likePattern());
        params.addAll(parts.params());
        params.add(criteria.limitPerScope());
        return jdbcTemplate.query(sql, this::briefingItem, params.toArray());
    }

    private List<Map<String, Object>> searchCardNews(SearchCriteria criteria) {
        String searchText = "cn.global_search_text";
        SqlParts parts = baseWhere(criteria, "cn.created_at::date", searchText);
        String sql = """
                SELECT
                    cn.id,
                    cn.title,
                    COALESCE(NULLIF(cn.primary_keyword_category, ''), NULLIF(cn.event_type, ''), NULLIF(cn.importance, ''), '카드뉴스') AS subtitle,
                    COALESCE(pc.name, cn.company) AS company_name,
                    cn.created_at::date AS item_date,
                    cn.importance,
                    cn.event_type,
                    CASE
                        WHEN lower(cn.title) = lower(?) THEN 110
                        WHEN lower(cn.title) LIKE lower(?) THEN 85
                        WHEN lower(array_to_string(cn.keywords, ' ')) LIKE lower(?) THEN 70
                        ELSE 40
                    END AS score
                FROM card_news cn
                LEFT JOIN peer_companies pc ON pc.id = COALESCE(cn.peer_company_id, cn.company)
                WHERE cn.status = 'ACTIVE' AND %s
                ORDER BY score DESC, cn.created_at DESC
                LIMIT ?
                """.formatted(parts.whereSql());
        List<Object> params = new ArrayList<>();
        params.add(criteria.query());
        params.add(criteria.likePattern());
        params.add(criteria.likePattern());
        params.addAll(parts.params());
        params.add(criteria.limitPerScope());
        return jdbcTemplate.query(sql, this::cardNewsItem, params.toArray());
    }

    private List<Map<String, Object>> searchKeywordGraph(SearchCriteria criteria) {
        SqlParts parts = new SqlParts();
        if (criteria.hasQuery()) {
            parts.add("lower(keyword) LIKE lower(?)", criteria.likePattern());
        }
        addDateRange(parts, criteria, "cn.created_at::date");
        String sql = """
                SELECT
                    keyword,
                    COUNT(*) AS hit_count,
                    MAX(cn.created_at::date) AS item_date,
                    array_agg(DISTINCT COALESCE(pc.name, cn.company) ORDER BY COALESCE(pc.name, cn.company)) AS companies,
                    MAX(CASE WHEN lower(keyword) = lower(?) THEN 100 ELSE 62 END) AS score
                FROM card_news cn
                LEFT JOIN peer_companies pc ON pc.id = COALESCE(cn.peer_company_id, cn.company)
                CROSS JOIN LATERAL unnest(COALESCE(cn.keywords, ARRAY[]::text[])) AS keyword
                WHERE cn.status = 'ACTIVE' AND %s
                GROUP BY keyword
                ORDER BY score DESC, hit_count DESC, item_date DESC
                LIMIT ?
                """.formatted(parts.hasConditions() ? parts.whereSql() : "TRUE");
        List<Object> params = new ArrayList<>();
        params.add(criteria.query());
        params.addAll(parts.params());
        params.add(criteria.limitPerScope());
        return jdbcTemplate.query(sql, this::keywordGraphItem, params.toArray());
    }

    private List<Map<String, Object>> searchPeers(SearchCriteria criteria) {
        String searchText = "pc.global_search_text";
        SqlParts parts = baseWhere(criteria, "COALESCE(pc.financial_updated_at, pc.created_at)::date", searchText);
        String sql = """
                SELECT
                    pc.id,
                    pc.name,
                    pc.tier,
                    COALESCE(array_to_string(pc.core_keywords, ', '), array_to_string(pc.keywords, ', '), pc.tier) AS snippet,
                    COALESCE(pc.financial_updated_at, pc.created_at)::date AS item_date,
                    CASE
                        WHEN lower(pc.name) = lower(?) OR lower(pc.id) = lower(?) THEN 100
                        WHEN lower(pc.name) LIKE lower(?) THEN 80
                        ELSE 38
                    END AS score
                FROM peer_companies pc
                WHERE %s
                ORDER BY score DESC, COALESCE(pc.financial_updated_at, pc.created_at) DESC
                LIMIT ?
                """.formatted(parts.whereSql());
        List<Object> params = new ArrayList<>();
        params.add(criteria.query());
        params.add(criteria.query());
        params.add(criteria.likePattern());
        params.addAll(parts.params());
        params.add(criteria.limitPerScope());
        return jdbcTemplate.query(sql, this::peerItem, params.toArray());
    }

    private SqlParts baseWhere(SearchCriteria criteria, String dateExpression, String searchExpression) {
        SqlParts parts = new SqlParts();
        if (criteria.hasQuery()) {
            parts.add(searchExpression + " ILIKE ?", criteria.likePattern());
        }
        addDateRange(parts, criteria, dateExpression);
        if (!parts.hasConditions()) {
            parts.add("TRUE");
        }
        return parts;
    }

    private void addDateRange(SqlParts parts, SearchCriteria criteria, String dateExpression) {
        if (criteria.startDate() != null) {
            parts.add(dateExpression + " >= ?", Date.valueOf(criteria.startDate()));
        }
        if (criteria.endDate() != null) {
            parts.add(dateExpression + " <= ?", Date.valueOf(criteria.endDate()));
        }
    }

    private Map<String, Object> briefingItem(ResultSet rs, int rowNum) throws SQLException {
        return item(
                "BRIEFING",
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("snippet"),
                "브리핑",
                "briefings",
                rs.getString("item_date"),
                rs.getDouble("score"),
                Map.of("briefingType", nullToEmpty(rs.getString("briefing_type")), "status", nullToEmpty(rs.getString("status")))
        );
    }

    private Map<String, Object> cardNewsItem(ResultSet rs, int rowNum) throws SQLException {
        return item(
                "CARD_NEWS",
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("subtitle"),
                nullToDefault(rs.getString("company_name"), "카드뉴스"),
                "issues",
                rs.getString("item_date"),
                rs.getDouble("score"),
                Map.of("importance", nullToEmpty(rs.getString("importance")), "eventType", nullToEmpty(rs.getString("event_type")))
        );
    }

    private Map<String, Object> keywordGraphItem(ResultSet rs, int rowNum) throws SQLException {
        String keyword = rs.getString("keyword");
        return item(
                "KEYWORD_GRAPH",
                keyword,
                keyword,
                "관련 카드뉴스 " + rs.getLong("hit_count") + "건",
                "키워드 그래프",
                "keywordGraph",
                rs.getString("item_date"),
                rs.getDouble("score"),
                Map.of("companies", nullToEmpty(rs.getString("companies")))
        );
    }

    private Map<String, Object> peerItem(ResultSet rs, int rowNum) throws SQLException {
        return item(
                "PEER_PLUS",
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("snippet"),
                "Peer+",
                "peerPlus",
                rs.getString("item_date"),
                rs.getDouble("score"),
                Map.of("peerId", rs.getString("id"), "tier", nullToEmpty(rs.getString("tier")))
        );
    }

    private Map<String, Object> item(
            String type,
            String id,
            String title,
            String snippet,
            String badge,
            String target,
            String date,
            double score,
            Map<String, Object> metadata
    ) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("type", type);
        item.put("title", nullToDefault(title, id));
        item.put("snippet", nullToDefault(snippet, ""));
        item.put("badge", badge);
        item.put("target", target);
        item.put("targetId", id);
        item.put("date", date);
        item.put("score", score);
        item.put("metadata", metadata);
        return item;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String nullToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static double doubleValue(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private record SearchCriteria(
            String query,
            List<String> scopes,
            String period,
            LocalDate startDate,
            LocalDate endDate,
            LocalDate queryDate,
            int limit
    ) {
        static SearchCriteria from(Map<String, Object> request) {
            String query = Objects.toString(request == null ? "" : request.getOrDefault("query", ""), "").trim();
            String period = Objects.toString(request == null ? "all" : request.getOrDefault("period", "all"), "all");
            LocalDate explicitStart = parseDate(request == null ? null : request.get("startDate"));
            LocalDate explicitEnd = parseDate(request == null ? null : request.get("endDate"));
            LocalDate[] range = rangeFromPeriod(period, explicitStart, explicitEnd);
            return new SearchCriteria(
                    query,
                    normalizeScopes(request == null ? null : request.get("scopes")),
                    period,
                    range[0],
                    range[1],
                    parseDate(query),
                    intValue(request == null ? null : request.get("limit"), DEFAULT_LIMIT, 1, MAX_LIMIT)
            );
        }

        boolean hasQuery() {
            return !query.isBlank();
        }

        boolean includes(String scope) {
            return scopes.contains(scope);
        }

        String likePattern() {
            return "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        }

        int limitPerScope() {
            return Math.max(limit, 6);
        }

        private static List<String> normalizeScopes(Object rawScopes) {
            if (rawScopes instanceof List<?> values) {
                List<String> scopes = values.stream()
                        .map(value -> Objects.toString(value, "").trim().toUpperCase(Locale.ROOT))
                        .filter(ALL_SCOPES::contains)
                        .distinct()
                        .toList();
                if (!scopes.isEmpty()) {
                    return scopes;
                }
            }
            String raw = Objects.toString(rawScopes, "").trim().toUpperCase(Locale.ROOT);
            if (ALL_SCOPES.contains(raw)) {
                return List.of(raw);
            }
            return ALL_SCOPES;
        }

        private static LocalDate[] rangeFromPeriod(String period, LocalDate explicitStart, LocalDate explicitEnd) {
            if (explicitStart != null || explicitEnd != null) {
                return new LocalDate[]{explicitStart, explicitEnd};
            }
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
            return switch (period == null ? "all" : period) {
                case "7d" -> new LocalDate[]{today.minusDays(7), today};
                case "30d" -> new LocalDate[]{today.minusDays(30), today};
                case "90d" -> new LocalDate[]{today.minusDays(90), today};
                default -> new LocalDate[]{null, null};
            };
        }

        private static LocalDate parseDate(Object value) {
            String raw = Objects.toString(value, "").trim();
            if (raw.isBlank()) {
                return null;
            }
            List<String> candidates = List.of(raw, raw.replace('.', '-').replace('/', '-'));
            for (String candidate : candidates) {
                try {
                    return LocalDate.parse(candidate, DateTimeFormatter.ISO_LOCAL_DATE);
                } catch (DateTimeParseException ignored) {
                    // Try next candidate.
                }
            }
            return null;
        }

        private static int intValue(Object value, int defaultValue, int min, int max) {
            if (value instanceof Number number) {
                return Math.min(max, Math.max(min, number.intValue()));
            }
            try {
                return Math.min(max, Math.max(min, Integer.parseInt(Objects.toString(value, ""))));
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
    }

    private static final class SqlParts {
        private final List<String> conditions = new ArrayList<>();
        private final List<Object> params = new ArrayList<>();

        void add(String condition, Object... values) {
            conditions.add(condition);
            params.addAll(List.of(values));
        }

        boolean hasConditions() {
            return !conditions.isEmpty();
        }

        String whereSql() {
            return String.join(" AND ", conditions);
        }

        List<Object> params() {
            return params;
        }
    }
}
