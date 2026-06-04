package com.skala.axis.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class DashboardKeywordTrendChartService {
    private static final int CHART_DAYS = 7;
    private static final int MAX_GROUPS = 4;
    private static final List<String> FIXED_GROUPS = List.of("AI 에이전트", "LLM", "인프라", "네트워크");
    private static final List<String> DATALAB_GROUPS = List.of(
            "인프라",
            "IT 인프라",
            "AX",
            "생성형 AI",
            "GPU",
            "cloud",
            "프라이빗 클라우드",
            "하이브리드 클라우드",
            "네트워크",
            "스마트팩토리",
            "AI 에이전트",
            "LLM",
            "RAG",
            "SOC",
            "디지털 트윈",
            "Kubernetes",
            "랜섬웨어",
            "사이버보안",
            "제조 AX"
    );
    private static final DateTimeFormatter TREND_DATE_FORMATTER = DateTimeFormatter.ofPattern("MM.dd");
    private static final List<String> SERIES_COLORS = List.of(
            "#EE7501",
            "#1A3A91",
            "#E1002A",
            "#111111"
    );
    private static final String KEYWORD_TREND_SQL = """
            WITH trend_rows AS (
                SELECT
                    ra.id,
                    ra.collected_at,
                    COALESCE(ra.metadata ->> 'group_name', ra.company::jsonb ->> 0) AS group_name,
                    COALESCE((ra.metadata ->> 'period')::date, ra.published_at::date) AS period,
                    NULLIF(ra.metadata ->> 'ratio', '')::numeric AS ratio,
                    COALESCE(ra.metadata ->> 'time_unit', 'date') AS time_unit,
                    COALESCE(ra.metadata ->> 'source', ra.source_name) AS source_name,
                    ROW_NUMBER() OVER (
                        PARTITION BY
                            COALESCE(ra.metadata ->> 'group_name', ra.company::jsonb ->> 0),
                            COALESCE((ra.metadata ->> 'period')::date, ra.published_at::date)
                        ORDER BY ra.collected_at DESC NULLS LAST, ra.id DESC
                    ) AS rn
                FROM raw_articles ra
                WHERE ra.source_type = 'search_trend'
            ),
            deduped AS (
                SELECT
                    group_name,
                    period,
                    ratio,
                    source_name
                FROM trend_rows
                WHERE rn = 1
                  AND time_unit = 'date'
                  AND group_name IS NOT NULL
                  AND group_name IN (:allowedGroups)
                  AND period IS NOT NULL
                  AND ratio IS NOT NULL
            ),
            recent_periods AS (
                SELECT period
                FROM deduped
                GROUP BY period
                ORDER BY period DESC
                LIMIT :historySize
            ),
            history AS (
                SELECT
                    d.*,
                    LAG(d.ratio) OVER (
                        PARTITION BY d.group_name
                        ORDER BY d.period
                    ) AS prev_ratio
                FROM deduped d
                WHERE d.period IN (SELECT period FROM recent_periods)
            ),
            windowed AS (
                SELECT
                    CAST(NULL AS INTEGER) AS group_rank,
                    FIRST_VALUE(d.ratio) OVER (
                        PARTITION BY d.group_name
                        ORDER BY d.period DESC
                    ) AS latest_ratio,
                    d.group_name,
                    d.period,
                    d.ratio,
                    d.source_name,
                    d.prev_ratio
                FROM history d
            )
            SELECT
                group_rank,
                latest_ratio,
                group_name,
                period,
                ratio,
                prev_ratio,
                ROUND(ratio - COALESCE(prev_ratio, ratio), 2) AS ratio_delta,
                source_name
            FROM windowed
            ORDER BY period ASC, group_name ASC
            """;
    private static final String KEYWORD_CAUSE_SQL = """
            SELECT
                id,
                source_name,
                title,
                published_at,
                LEFT(COALESCE(content, ''), 240) AS snippet
            FROM raw_articles
            WHERE source_type <> 'search_trend'
              AND COALESCE(published_at::date, collected_at::date) BETWEEN :startDate AND :endDate
              AND (
                  title ILIKE :keywordPattern
                  OR content ILIKE :keywordPattern
              )
            ORDER BY COALESCE(published_at, collected_at) DESC NULLS LAST, id DESC
            LIMIT 3
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Value("${axis.dashboard.keyword-spike-delta-threshold:40}")
    private BigDecimal spikeDeltaThreshold;

    @Value("${axis.dashboard.keyword-trends-cache-ttl-seconds:900}")
    private long cacheTtlSeconds;

    private volatile CachedKeywordTrendPayload cachedPayload = new CachedKeywordTrendPayload(KeywordTrendPayload.empty(), Instant.EPOCH);

    @PostConstruct
    public void warmKeywordTrendChartCacheOnStartup() {
        refreshKeywordTrendChartCache();
    }

    @Scheduled(fixedDelayString = "${axis.dashboard.keyword-trends-refresh-ms:900000}", initialDelayString = "${axis.dashboard.keyword-trends-initial-delay-ms:60000}")
    public void refreshKeywordTrendChartCache() {
        cachedPayload = new CachedKeywordTrendPayload(loadKeywordTrendChart(CHART_DAYS), Instant.now());
        log.debug("Dashboard keyword trend chart cache refreshed | points={} series={}",
                cachedPayload.payload().searchPoints().size(),
                cachedPayload.payload().series().size());
    }

    public Map<String, Object> getCachedKeywordTrendChart() {
        CachedKeywordTrendPayload current = cachedPayload;
        return current.payload().toResponseMap(current.cachedAt(), isExpired(current.cachedAt()));
    }

    public Map<String, Object> applyKeywordTrendChart(Map<String, Object> dashboardSummary) {
        try {
            KeywordTrendPayload livePayload = loadKeywordTrendChart(CHART_DAYS);
            if (!livePayload.searchPoints().isEmpty() && !livePayload.series().isEmpty()) {
                dashboardSummary.put("keywordSearchPoints", livePayload.searchPoints());
                dashboardSummary.put("keywordSeries", livePayload.series());
                dashboardSummary.put("keywordInsights", livePayload.insights());
                return dashboardSummary;
            }
        } catch (RuntimeException ignored) {
            // Keep the dashboard alive even if the keyword trend read-model hits a
            // schema/data edge case on a specific environment.
        }

        // Never leave fixture keyword chart data behind when DB enrichment fails.
        // An empty chart is less misleading than showing mock series names/values.
        dashboardSummary.put("keywordSearchPoints", List.of());
        dashboardSummary.put("keywordSeries", List.of());
        dashboardSummary.put("keywordInsights", List.of());
        return dashboardSummary;
    }

    public Map<String, Object> removeKeywordTrendChart(Map<String, Object> dashboardSummary) {
        dashboardSummary.put("keywordSearchPoints", List.of());
        dashboardSummary.put("keywordSeries", List.of());
        dashboardSummary.put("keywordInsights", List.of());
        return dashboardSummary;
    }

    private boolean isExpired(Instant cachedAt) {
        if (cachedAt == null || Instant.EPOCH.equals(cachedAt)) {
            return true;
        }
        return Duration.between(cachedAt, Instant.now()).getSeconds() >= cacheTtlSeconds;
    }

    private KeywordTrendPayload loadKeywordTrendChart(int chartDays) {
        try {
            List<KeywordTrendRow> rows = queryKeywordTrendRows(KEYWORD_TREND_SQL, chartDays);
            if (rows.isEmpty()) {
                return KeywordTrendPayload.empty();
            }

            List<KeywordTrendRow> displayedRows = selectDisplayedRows(rows);
            if (displayedRows.isEmpty()) {
                return KeywordTrendPayload.empty();
            }

            Map<Integer, SeriesMeta> seriesByRank = new LinkedHashMap<>();
            Map<LocalDate, Map<String, Object>> pointsByDate = new LinkedHashMap<>();
            LinkedHashSet<String> sourceNames = new LinkedHashSet<>();

            for (KeywordTrendRow row : displayedRows) {
                if (row.groupRank() == null || row.groupRank() < 1 || row.groupRank() > MAX_GROUPS) {
                    continue;
                }

                SeriesMeta series = seriesByRank.computeIfAbsent(
                        row.groupRank(),
                        rank -> new SeriesMeta(
                                "trend" + rank,
                                row.groupName(),
                                SERIES_COLORS.get(Math.min(rank - 1, SERIES_COLORS.size() - 1)),
                                formatRatioValue(row.latestRatio())
                        )
                );

                Map<String, Object> point = pointsByDate.computeIfAbsent(
                        row.period(),
                        period -> new LinkedHashMap<>(Map.of("date", TREND_DATE_FORMATTER.format(period)))
                );
                point.put(series.key(), toScaledDouble(row.ratioDelta()));
                point.put(series.key() + "Ratio", toScaledDouble(row.ratio()));
                addIfPresent(sourceNames, row.sourceName());
            }

            List<Map<String, Object>> searchPoints = pointsByDate.values().stream()
                    .filter(point -> point.size() > 1)
                    .toList();
            List<Map<String, Object>> series = seriesByRank.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> entry.getValue().toMap())
                    .toList();

            if (searchPoints.isEmpty() || series.isEmpty()) {
                return KeywordTrendPayload.empty();
            }

            return new KeywordTrendPayload(
                    searchPoints,
                    series,
                    buildSpikeInsights(displayedRows, seriesByRank),
                    String.join(", ", sourceNames)
            );
        } catch (DataAccessException ignored) {
            return KeywordTrendPayload.empty();
        }
    }

    private List<KeywordTrendRow> queryKeywordTrendRows(String sql, int chartDays) {
        try {
            return jdbcTemplate.query(
                    sql,
                    new MapSqlParameterSource()
                            .addValue("historySize", chartDays)
                            .addValue("maxGroups", MAX_GROUPS)
                            .addValue("spikeDeltaThreshold", spikeDeltaThreshold)
                            .addValue("allowedGroups", DATALAB_GROUPS),
                    this::mapKeywordTrendRow
            );
        } catch (DataAccessException ignored) {
            return List.of();
        }
    }

    private KeywordTrendRow mapKeywordTrendRow(ResultSet rs, int rowNum) throws SQLException {
        Object rawRank = rs.getObject("group_rank");
        Integer rank = rawRank instanceof Number number ? number.intValue() : null;
        return new KeywordTrendRow(
                rank,
                rs.getBigDecimal("latest_ratio"),
                rs.getString("group_name"),
                rs.getObject("period", LocalDate.class),
                rs.getBigDecimal("ratio"),
                rs.getBigDecimal("prev_ratio"),
                rs.getBigDecimal("ratio_delta"),
                rs.getString("source_name")
        );
    }

    private List<KeywordTrendRow> selectDisplayedRows(List<KeywordTrendRow> rows) {
        Map<String, KeywordTrendRow> latestByGroup = new LinkedHashMap<>();
        for (KeywordTrendRow row : rows) {
            KeywordTrendRow current = latestByGroup.get(row.groupName());
            if (current == null || row.period().isAfter(current.period())) {
                latestByGroup.put(row.groupName(), row);
            }
        }

        Map<String, KeywordTrendRow> strongestSpikeByGroup = new LinkedHashMap<>();
        for (KeywordTrendRow row : rows) {
            if (FIXED_GROUPS.contains(row.groupName()) || !isSpikeRow(row)) {
                continue;
            }

            KeywordTrendRow current = strongestSpikeByGroup.get(row.groupName());
            if (current == null || compareSpikeStrength(row, current) < 0) {
                strongestSpikeByGroup.put(row.groupName(), row);
            }
        }

        List<KeywordTrendRow> spikeRows = strongestSpikeByGroup.values().stream()
                .filter(row -> !FIXED_GROUPS.contains(row.groupName()))
                .sorted(
                        Comparator.comparing((KeywordTrendRow row) -> absOrZero(row.ratioDelta()), Comparator.reverseOrder())
                                .thenComparing(KeywordTrendRow::period, Comparator.reverseOrder())
                                .thenComparing(KeywordTrendRow::latestRatio, Comparator.nullsLast(Comparator.reverseOrder()))
                                .thenComparing(KeywordTrendRow::groupName)
                )
                .limit(MAX_GROUPS)
                .toList();

        int fixedSlots = Math.max(MAX_GROUPS - spikeRows.size(), 0);
        LinkedHashSet<String> fixedToKeep = latestByGroup.values().stream()
                .filter(row -> FIXED_GROUPS.contains(row.groupName()))
                .sorted(
                        Comparator.comparing(
                                        (KeywordTrendRow row) -> absOrZero(row.ratioDelta()),
                                        Comparator.reverseOrder()
                                )
                                .thenComparing(row -> FIXED_GROUPS.indexOf(row.groupName()))
                )
                .limit(fixedSlots)
                .map(KeywordTrendRow::groupName)
                .collect(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new)
                );

        List<String> selectedGroups = new ArrayList<>();
        for (String fixedGroup : FIXED_GROUPS) {
            if (fixedToKeep.contains(fixedGroup)) {
                selectedGroups.add(fixedGroup);
            }
        }
        for (KeywordTrendRow spikeRow : spikeRows) {
            selectedGroups.add(spikeRow.groupName());
        }

        Map<String, Integer> rankByGroup = new LinkedHashMap<>();
        for (int index = 0; index < selectedGroups.size() && index < MAX_GROUPS; index++) {
            rankByGroup.put(selectedGroups.get(index), index + 1);
        }

        return rows.stream()
                .filter(row -> rankByGroup.containsKey(row.groupName()))
                .map(row -> row.withRank(rankByGroup.get(row.groupName())))
                .sorted(
                        Comparator.comparing(KeywordTrendRow::period)
                                .thenComparing(KeywordTrendRow::groupRank)
                )
                .toList();
    }

    private BigDecimal absOrZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.abs();
    }

    private int compareSpikeStrength(KeywordTrendRow left, KeywordTrendRow right) {
        return Comparator.comparing((KeywordTrendRow row) -> absOrZero(row.ratioDelta()), Comparator.reverseOrder())
                .thenComparing(KeywordTrendRow::period, Comparator.reverseOrder())
                .thenComparing(KeywordTrendRow::latestRatio, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(KeywordTrendRow::groupName)
                .compare(left, right);
    }

    private boolean isSpikeRow(KeywordTrendRow row) {
        return row.prevRatio() != null
                && row.ratioDelta() != null
                && row.ratioDelta().abs().compareTo(spikeDeltaThreshold.abs()) >= 0;
    }

    private List<Map<String, Object>> buildSpikeInsights(
            List<KeywordTrendRow> rows,
            Map<Integer, SeriesMeta> seriesByRank
    ) {
        return rows.stream()
                .filter(row -> row.groupRank() != null && row.groupRank() >= 1 && row.groupRank() <= MAX_GROUPS)
                .filter(this::isSpikeRow)
                .sorted(
                        Comparator.comparing(KeywordTrendRow::period)
                                .thenComparing(KeywordTrendRow::groupRank)
                )
                .map(row -> {
                    SeriesMeta series = seriesByRank.get(row.groupRank());
                    String key = series == null ? "trend" + row.groupRank() : series.key();
                    List<Map<String, Object>> evidence = queryCauseEvidence(row.groupName(), row.period());
                    String skAxPoint = buildSkAxPoint(evidence);
                    String directionLabel = row.ratioDelta().signum() < 0 ? "급락" : "급등";
                    String reason = buildCauseReason(row.groupName(), directionLabel, evidence);

                    Map<String, Object> insight = new LinkedHashMap<>();
                    insight.put("key", key);
                    insight.put("time", TREND_DATE_FORMATTER.format(row.period()));
                    insight.put("title", row.groupName() + " 검색지수 " + directionLabel);
                    insight.put("valueLabel", formatRatioValue(row.ratio()) + " / " + formatDeltaValue(row.ratioDelta()));
                    insight.put("reason", reason);
                    insight.put("skAxPoint", skAxPoint);
                    insight.put("evidence", evidence);
                    return insight;
                })
                .toList();
    }

    private List<Map<String, Object>> queryCauseEvidence(String keyword, LocalDate period) {
        if (keyword == null || keyword.isBlank() || period == null) {
            return List.of();
        }
        try {
            return jdbcTemplate.queryForList(
                    KEYWORD_CAUSE_SQL,
                    new MapSqlParameterSource()
                            .addValue("keywordPattern", "%" + keyword + "%")
                            .addValue("startDate", period.minusDays(3))
                            .addValue("endDate", period.plusDays(1))
            );
        } catch (DataAccessException ignored) {
            return List.of();
        }
    }

    private String buildCauseReason(String keyword, String directionLabel, List<Map<String, Object>> evidence) {
        if (evidence.isEmpty()) {
            return keyword + " 검색지수가 전일 대비 크게 변동했습니다. 아직 raw_articles에서 직접 연결되는 원문 근거가 충분하지 않아 웹 서치 보강이 필요한 상태입니다.";
        }
        Object title = evidence.get(0).get("title");
        return directionLabel + "일 전후 raw_articles에서 '" + keyword + "' 관련 원문이 확인됐습니다. 대표 근거는 \"" + title + "\"입니다.";
    }

    private String buildSkAxPoint(List<Map<String, Object>> evidence) {
        if (evidence.isEmpty()) {
            return "후속 분석에서는 같은 기간 뉴스/공식 발표를 웹 서치로 보강해 실제 이슈인지 검증해야 합니다.";
        }
        return "원인 후보는 raw_articles 근거 " + evidence.size() + "건을 기반으로 한 1차 분석입니다. 통합 분석 agent에서 출처 다양성과 시점 일치성을 추가 검증해야 합니다.";
    }

    private String formatRatioValue(BigDecimal value) {
        if (value == null) {
            return "-";
        }
        return value.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    private String formatDeltaValue(BigDecimal value) {
        if (value == null) {
            return "-";
        }
        return (value.signum() > 0 ? "+" : "") + value.setScale(2, RoundingMode.HALF_UP).toPlainString() + "pt";
    }

    private Double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Double toScaledDouble(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private void addIfPresent(LinkedHashSet<String> target, String value) {
        if (value != null && !value.isBlank()) {
            target.add(value);
        }
    }

    private record KeywordTrendRow(
            Integer groupRank,
            BigDecimal latestRatio,
            String groupName,
            LocalDate period,
            BigDecimal ratio,
            BigDecimal prevRatio,
            BigDecimal ratioDelta,
            String sourceName
    ) {
        private KeywordTrendRow withRank(Integer nextRank) {
            return new KeywordTrendRow(
                    nextRank,
                    latestRatio,
                    groupName,
                    period,
                    ratio,
                    prevRatio,
                    ratioDelta,
                    sourceName
            );
        }
    }

    private record SeriesMeta(
            String key,
            String name,
            String color,
            String total
    ) {
        private Map<String, Object> toMap() {
            Map<String, Object> mapped = new LinkedHashMap<>();
            mapped.put("key", key);
            mapped.put("name", name);
            mapped.put("color", color);
            mapped.put("total", total);
            return mapped;
        }
    }

    private record KeywordTrendPayload(
            List<Map<String, Object>> searchPoints,
            List<Map<String, Object>> series,
            List<Map<String, Object>> insights,
            String sourceName
    ) {
        private static KeywordTrendPayload empty() {
            return new KeywordTrendPayload(List.of(), List.of(), List.of(), null);
        }

        private Map<String, Object> toResponseMap(Instant cachedAt, boolean stale) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("keywordSearchPoints", searchPoints);
            response.put("keywordSeries", series);
            response.put("keywordInsights", insights);
            response.put("sourceName", sourceName);
            response.put("cachedAt", cachedAt == null || Instant.EPOCH.equals(cachedAt) ? null : cachedAt.toString());
            response.put("stale", stale);
            return response;
        }
    }

    private record CachedKeywordTrendPayload(
            KeywordTrendPayload payload,
            Instant cachedAt
    ) {
        private CachedKeywordTrendPayload {
            if (payload == null) {
                payload = KeywordTrendPayload.empty();
            }
            if (cachedAt == null) {
                cachedAt = Instant.EPOCH;
            }
        }
    }
}
