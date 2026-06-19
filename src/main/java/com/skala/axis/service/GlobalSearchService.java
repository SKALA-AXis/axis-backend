/*
 * 작성일: 2026-05-22
 * 작성자: 박진
 * 변경이력:
 *   2026-05-22 박진 — 카드뉴스 대폭 수정 및 알림 설정 작업
 *   2026-05-29 안가은 — 카드뉴스 소프트삭제·관리자 감사로그 API 추가 및 검색 API 보강
 *   2026-06-12 최종민 — 검색 화면 CARD_NEWS를 AI 의미 검색(Qdrant)으로 위임
 */
package com.skala.axis.service;

import com.skala.axis.config.AxisTime;
import com.skala.axis.dto.SearchRequest;
import com.skala.axis.dto.SearchResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.skala.axis.query.GlobalSearchQueries.BRIEFING_DATE;
import static com.skala.axis.query.GlobalSearchQueries.BRIEFING_SEARCH_TEXT;
import static com.skala.axis.query.GlobalSearchQueries.CARD_NEWS_DATE;
import static com.skala.axis.query.GlobalSearchQueries.CARD_NEWS_SEARCH_TEXT;
import static com.skala.axis.query.GlobalSearchQueries.PEER_DATE;
import static com.skala.axis.query.GlobalSearchQueries.PEER_SEARCH_TEXT;
import static com.skala.axis.query.GlobalSearchQueries.briefingSearchSql;
import static com.skala.axis.query.GlobalSearchQueries.cardNewsHydrateSql;
import static com.skala.axis.query.GlobalSearchQueries.cardNewsKeywordSql;
import static com.skala.axis.query.GlobalSearchQueries.peerSearchSql;

@Slf4j
@Service
@RequiredArgsConstructor
public class GlobalSearchService {
    private static final int DEFAULT_LIMIT = 8;
    private static final int MAX_LIMIT = 30;
    private static final List<String> ALL_SCOPES = List.of("BRIEFING", "CARD_NEWS", "PEER_PLUS");
    // AI 의미 검색이 이 시간 안에 못 돌아오면 ILIKE 키워드 검색으로 폴백한다.
    private static final Duration SEMANTIC_SEARCH_BUDGET = Duration.ofSeconds(3);
    // 의미 검색 1위 = 95점: 제목 완전일치(110)보다는 낮고 부분일치(85)보다는 높게 끼워 넣는다.
    private static final double SEMANTIC_SCORE_TOP = 95.0;
    private static final double SEMANTIC_SCORE_STEP = 4.0;
    private static final double SEMANTIC_SCORE_FLOOR = 50.0;

    private final JdbcTemplate jdbcTemplate;
    private final AiClientService aiClientService;

    @Value("${axis.search.semantic.enabled:true}")
    private boolean semanticSearchEnabled;

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

        // 전체 점수순 상위 limit 만 자르면 최고점 scope(briefing 130/120…)가 limit 을 독식해
        // card/peer(110/85, 100/80)가 결과에서 통째로 사라진다. scope 라운드로빈으로 각 scope 의
        // 상위 항목을 번갈아 담아 limit 까지 채워, 총량(limit)은 지키되 어느 scope 도 굶지 않게 한다.
        // (items 는 위에서 점수순 정렬됨 → bucket 내 순서도 점수순)
        Map<String, List<Map<String, Object>>> byScope = new LinkedHashMap<>();
        for (String scope : ALL_SCOPES) {
            byScope.put(scope, new ArrayList<>());
        }
        for (Map<String, Object> item : items) {
            List<Map<String, Object>> bucket = byScope.get(String.valueOf(item.get("type")));
            if (bucket != null) {
                bucket.add(item);
            }
        }
        List<Map<String, Object>> limitedItems = new ArrayList<>();
        int[] cursor = new int[ALL_SCOPES.size()];
        boolean progressed = true;
        while (limitedItems.size() < criteria.limit() && progressed) {
            progressed = false;
            for (int i = 0; i < ALL_SCOPES.size() && limitedItems.size() < criteria.limit(); i++) {
                List<Map<String, Object>> bucket = byScope.get(ALL_SCOPES.get(i));
                if (cursor[i] < bucket.size()) {
                    limitedItems.add(bucket.get(cursor[i]++));
                    progressed = true;
                }
            }
        }
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
        String termMatchSql = briefingTermMatchSql(criteria, BRIEFING_SEARCH_TEXT);
        SqlParts parts = new SqlParts();
        if (criteria.hasQuery()) {
            if (criteria.queryDate() != null) {
                List<Object> values = new ArrayList<>();
                values.add(criteria.likePattern());
                values.addAll(criteria.termLikePatterns());
                values.add(Date.valueOf(criteria.queryDate()));
                parts.add("(" + BRIEFING_SEARCH_TEXT + " ILIKE ? OR " + termMatchSql + " OR " + BRIEFING_DATE + " = ?)",
                        values.toArray());
            } else if (criteria.termLikePatterns().isEmpty()) {
                parts.add(BRIEFING_SEARCH_TEXT + " ILIKE ?", criteria.likePattern());
            } else {
                List<Object> values = new ArrayList<>();
                values.add(criteria.likePattern());
                values.addAll(criteria.termLikePatterns());
                parts.add("(" + BRIEFING_SEARCH_TEXT + " ILIKE ? OR " + termMatchSql + ")", values.toArray());
            }
        }
        addDateRange(parts, criteria, BRIEFING_DATE);
        if (!parts.hasConditions()) {
            parts.add("TRUE");
        }
        String sql = briefingSearchSql(termMatchSql, parts.whereSql());
        List<Object> params = new ArrayList<>();
        params.add(criteria.query());
        params.add(criteria.queryDate() == null ? Date.valueOf(LocalDate.of(1900, 1, 1)) : Date.valueOf(criteria.queryDate()));
        params.add(criteria.likePattern());
        params.add(criteria.likePattern());
        params.add(criteria.likePattern());
        params.add(criteria.likePattern());
        params.addAll(criteria.termLikePatterns());
        params.addAll(parts.params());
        params.add(criteria.limitPerScope());
        return jdbcTemplate.query(sql, this::briefingItem, params.toArray());
    }

    private String briefingTermMatchSql(SearchCriteria criteria, String searchText) {
        if (criteria.termLikePatterns().isEmpty()) {
            return "FALSE";
        }
        return criteria.termLikePatterns().stream()
                .map(ignored -> searchText + " ILIKE ?")
                .reduce((left, right) -> left + " AND " + right)
                .orElse("FALSE");
    }

    private List<Map<String, Object>> searchCardNews(SearchCriteria criteria) {
        List<Map<String, Object>> semantic = criteria.hasQuery() && semanticSearchEnabled
                ? searchCardNewsSemantic(criteria)
                : List.of();
        if (semantic.size() >= criteria.limitPerScope()) {
            return semantic;
        }
        List<Map<String, Object>> keyword = searchCardNewsKeyword(criteria);
        if (semantic.isEmpty()) {
            return keyword;
        }
        // 의미 검색 결과 우선, 키워드 결과는 중복 제거 후 보충.
        Set<Object> seenIds = new LinkedHashSet<>();
        semantic.forEach(item -> seenIds.add(item.get("id")));
        List<Map<String, Object>> merged = new ArrayList<>(semantic);
        keyword.stream()
                .filter(item -> !seenIds.contains(item.get("id")))
                .limit(Math.max(0, criteria.limitPerScope() - merged.size()))
                .forEach(merged::add);
        return merged;
    }

    /**
     * axis-ai /search (BGE-M3 하이브리드 + 리랭킹) 위임 — 동의어·의미 매칭.
     * SearchHit.rdb_id(raw_articles FK)를 card_news.primary_raw_article_id 로 역매핑해
     * 화면 아이템으로 복원한다. AI 장애·시간 초과·결과 없음은 전부 빈 목록 → 호출부가 ILIKE 폴백.
     */
    private List<Map<String, Object>> searchCardNewsSemantic(SearchCriteria criteria) {
        List<SearchResponse.Hit> hits;
        try {
            SearchResponse response = aiClientService
                    .search(new SearchRequest(criteria.query(), null, null, criteria.limitPerScope()))
                    .block(SEMANTIC_SEARCH_BUDGET);
            hits = response == null || response.getHits() == null ? List.of() : response.getHits();
        } catch (Exception e) {
            log.debug("semantic search 폴백(ILIKE) | query={} cause={}", criteria.query(), e.toString());
            return List.of();
        }
        List<Long> rdbIds = hits.stream()
                .map(SearchResponse.Hit::getRdbId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (rdbIds.isEmpty()) {
            return List.of();
        }

        Map<Long, Map<String, Object>> rowsByRdbId = hydrateCardNewsByRawArticleIds(criteria, rdbIds);
        List<Map<String, Object>> items = new ArrayList<>();
        Set<Object> seenCardIds = new LinkedHashSet<>();
        for (Long rdbId : rdbIds) {
            Map<String, Object> row = rowsByRdbId.get(rdbId);
            if (row == null || !seenCardIds.add(row.get("id"))) {
                continue;
            }
            double score = Math.max(SEMANTIC_SCORE_FLOOR, SEMANTIC_SCORE_TOP - SEMANTIC_SCORE_STEP * items.size());
            items.add(item(
                    "CARD_NEWS",
                    stringValue(row.get("id")),
                    stringValue(row.get("title")),
                    stringValue(row.get("subtitle")),
                    nullToDefault(stringValue(row.get("company_name")), "카드뉴스"),
                    "issues",
                    stringValue(row.get("item_date")),
                    score,
                    Map.of(
                            "importance", nullToEmpty(stringValue(row.get("importance"))),
                            "eventType", nullToEmpty(stringValue(row.get("event_type")))
                    )
            ));
        }
        return items;
    }

    private Map<Long, Map<String, Object>> hydrateCardNewsByRawArticleIds(SearchCriteria criteria, List<Long> rdbIds) {
        SqlParts parts = new SqlParts();
        addDateRange(parts, criteria, CARD_NEWS_DATE);
        String dateFilter = parts.hasConditions() ? " AND " + parts.whereSql() : "";
        String placeholders = String.join(", ", java.util.Collections.nCopies(rdbIds.size(), "?"));
        String sql = cardNewsHydrateSql(placeholders, dateFilter);
        List<Object> params = new ArrayList<>(rdbIds);
        params.addAll(parts.params());
        List<Map<String, Object>> rows = jdbcTemplate.query(sql, this::cardNewsHydrateRow, params.toArray());
        Map<Long, Map<String, Object>> byRdbId = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object rdbId = row.get("primary_raw_article_id");
            if (rdbId instanceof Number number) {
                byRdbId.putIfAbsent(number.longValue(), row);
            }
        }
        return byRdbId;
    }

    private Map<String, Object> cardNewsHydrateRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getString("id"));
        row.put("title", rs.getString("title"));
        row.put("subtitle", rs.getString("subtitle"));
        row.put("company_name", rs.getString("company_name"));
        row.put("item_date", rs.getString("item_date"));
        row.put("importance", rs.getString("importance"));
        row.put("event_type", rs.getString("event_type"));
        row.put("primary_raw_article_id", rs.getLong("primary_raw_article_id"));
        return row;
    }

    private List<Map<String, Object>> searchCardNewsKeyword(SearchCriteria criteria) {
        SqlParts parts = baseWhere(criteria, CARD_NEWS_DATE, CARD_NEWS_SEARCH_TEXT);
        String sql = cardNewsKeywordSql(parts.whereSql());
        List<Object> params = new ArrayList<>();
        params.add(criteria.query());
        params.add(criteria.likePattern());
        params.add(criteria.likePattern());
        params.addAll(parts.params());
        params.add(criteria.limitPerScope());
        return jdbcTemplate.query(sql, this::cardNewsItem, params.toArray());
    }

    private List<Map<String, Object>> searchPeers(SearchCriteria criteria) {
        SqlParts parts = baseWhere(criteria, PEER_DATE, PEER_SEARCH_TEXT);
        String sql = peerSearchSql(parts.whereSql());
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
            if (criteria.termLikePatterns().isEmpty()) {
                parts.add(searchExpression + " ILIKE ?", criteria.likePattern());
            } else {
                // 전체 일치 OR 토큰 전부 포함 — "삼성 sds"(공백) 가 "삼성SDS" 를 잡도록(브리핑 검색과 동일 규칙).
                String termMatch = criteria.termLikePatterns().stream()
                        .map(ignored -> searchExpression + " ILIKE ?")
                        .reduce((left, right) -> left + " AND " + right)
                        .orElse("FALSE");
                List<Object> values = new ArrayList<>();
                values.add(criteria.likePattern());
                values.addAll(criteria.termLikePatterns());
                parts.add("(" + searchExpression + " ILIKE ? OR (" + termMatch + "))", values.toArray());
            }
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

        List<String> termLikePatterns() {
            if (!hasQuery()) {
                return List.of();
            }
            return java.util.Arrays.stream(query.split("\\s+"))
                    .map(String::trim)
                    .filter(term -> !term.isBlank())
                    .distinct()
                    .map(term -> "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
                    .toList();
        }

        int limitPerScope() {
            return Math.max(limit, 6);
        }

        private static List<String> normalizeScopes(Object rawScopes) {
            if (rawScopes instanceof List<?> values) {
                List<String> scopes = values.stream()
                        .map(SearchCriteria::normalizeScope)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();
                if (!scopes.isEmpty()) {
                    return scopes;
                }
            }
            String normalized = normalizeScope(rawScopes);
            if (normalized != null) {
                return List.of(normalized);
            }
            return ALL_SCOPES;
        }

        private static String normalizeScope(Object rawScope) {
            String raw = Objects.toString(rawScope, "").trim().toUpperCase(Locale.ROOT);
            return switch (raw) {
                case "BRIEFING", "BRIEFINGS" -> "BRIEFING";
                case "CARD_NEWS", "CARDS" -> "CARD_NEWS";
                case "PEER_PLUS", "PEERS" -> "PEER_PLUS";
                default -> null;
            };
        }

        private static LocalDate[] rangeFromPeriod(String period, LocalDate explicitStart, LocalDate explicitEnd) {
            if (explicitStart != null || explicitEnd != null) {
                return new LocalDate[]{explicitStart, explicitEnd};
            }
            LocalDate today = AxisTime.today();
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
