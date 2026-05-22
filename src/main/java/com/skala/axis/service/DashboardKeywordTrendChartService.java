package com.skala.axis.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
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
public class DashboardKeywordTrendChartService {
    private static final int CHART_DAYS = 7;
    private static final int MAX_GROUPS = 4;
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
            latest_group_period AS (
                SELECT
                    d.group_name,
                    d.period,
                    d.ratio,
                    ROW_NUMBER() OVER (
                        PARTITION BY d.group_name
                        ORDER BY d.period DESC
                    ) AS latest_rank
                FROM deduped d
                WHERE d.period IN (SELECT period FROM recent_periods)
            ),
            group_rank_candidates AS (
                SELECT
                    lgp.group_name,
                    lgp.ratio AS latest_ratio,
                    (
                        SELECT AVG(d.ratio)
                        FROM deduped d
                        WHERE d.group_name = lgp.group_name
                          AND d.period IN (SELECT period FROM recent_periods)
                    ) AS avg_ratio
                FROM latest_group_period lgp
                WHERE lgp.latest_rank = 1
            ),
            ranked_groups AS (
                SELECT
                    group_name,
                    latest_ratio,
                    ROW_NUMBER() OVER (
                        ORDER BY latest_ratio DESC NULLS LAST, avg_ratio DESC, group_name ASC
                    ) AS group_rank
                FROM group_rank_candidates
                ORDER BY latest_ratio DESC NULLS LAST, avg_ratio DESC, group_name ASC
                LIMIT :maxGroups
            ),
            windowed AS (
                SELECT
                    rg.group_rank,
                    rg.latest_ratio,
                    d.group_name,
                    d.period,
                    d.ratio,
                    d.source_name,
                    LAG(d.ratio) OVER (
                        PARTITION BY d.group_name
                        ORDER BY d.period
                    ) AS prev_ratio
                FROM deduped d
                JOIN ranked_groups rg
                    ON rg.group_name = d.group_name
                WHERE d.period IN (SELECT period FROM recent_periods)
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
            ORDER BY period ASC, group_rank ASC
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public Map<String, Object> applyKeywordTrendChart(Map<String, Object> dashboardSummary) {
        try {
            KeywordTrendPayload livePayload = loadKeywordTrendChart(CHART_DAYS);
            if (!livePayload.searchPoints().isEmpty() && !livePayload.series().isEmpty()) {
                dashboardSummary.put("keywordSearchPoints", livePayload.searchPoints());
                dashboardSummary.put("keywordSeries", livePayload.series());
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
        return dashboardSummary;
    }

    private KeywordTrendPayload loadKeywordTrendChart(int chartDays) {
        try {
            List<KeywordTrendRow> rows = queryKeywordTrendRows(KEYWORD_TREND_SQL, chartDays);
            if (rows.isEmpty()) {
                return KeywordTrendPayload.empty();
            }

            Map<Integer, SeriesMeta> seriesByRank = new LinkedHashMap<>();
            Map<LocalDate, Map<String, Object>> pointsByDate = new LinkedHashMap<>();
            LinkedHashSet<String> sourceNames = new LinkedHashSet<>();

            for (KeywordTrendRow row : rows) {
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

            return new KeywordTrendPayload(searchPoints, series, String.join(", ", sourceNames));
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
                            .addValue("maxGroups", MAX_GROUPS),
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
                rs.getBigDecimal("ratio_delta"),
                rs.getString("source_name")
        );
    }

    private String formatRatioValue(BigDecimal value) {
        if (value == null) {
            return "-";
        }
        return value.setScale(0, RoundingMode.HALF_UP).toPlainString();
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
            BigDecimal ratioDelta,
            String sourceName
    ) {}

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
            String sourceName
    ) {
        private static KeywordTrendPayload empty() {
            return new KeywordTrendPayload(List.of(), List.of(), null);
        }
    }
}
