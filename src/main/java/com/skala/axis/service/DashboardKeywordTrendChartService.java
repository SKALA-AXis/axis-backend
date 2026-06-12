package com.skala.axis.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static com.skala.axis.query.DashboardKeywordTrendQueries.BUSINESS_SIGNAL_SOURCE_BY_RAW_ARTICLE_SQL;
import static com.skala.axis.query.DashboardKeywordTrendQueries.BUSINESS_SIGNAL_SOURCE_SQL;
import static com.skala.axis.query.DashboardKeywordTrendQueries.CARD_NEWS_SOURCE_SQL;
import static com.skala.axis.query.DashboardKeywordTrendQueries.INTEGRATED_ISSUE_REPRESENTATIVE_SQL;
import static com.skala.axis.query.DashboardKeywordTrendQueries.INTEGRATED_ISSUE_SOURCE_ARTICLE_SQL;
import static com.skala.axis.query.DashboardKeywordTrendQueries.KEYWORD_CAUSE_SQL;
import static com.skala.axis.query.DashboardKeywordTrendQueries.KEYWORD_TREND_SQL;

@Service
@RequiredArgsConstructor
@Slf4j
public class DashboardKeywordTrendChartService {
    private static final int CHART_DAYS = 7;
    private static final int SPIKE_LOOKBACK_DAYS = 14;
    private static final int MAX_GROUPS = 4;
    private static final List<String> FIXED_GROUPS = List.of("AX", "사이버보안", "인프라", "수주");
    private static final DateTimeFormatter TREND_DATE_FORMATTER = DateTimeFormatter.ofPattern("MM.dd");
    private static final List<String> SERIES_COLORS = List.of(
            "#EE7501",
            "#1A3A91",
            "#E1002A",
            "#111111"
    );

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Value("${axis.dashboard.keyword-spike-delta-threshold:50}")
    private BigDecimal spikeDeltaThreshold;

    @Value("${axis.dashboard.keyword-spike-average-multiplier:2.5}")
    private BigDecimal spikeAverageMultiplier;

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

        // Never leave placeholder keyword chart data behind when DB enrichment fails.
        // An empty chart is less misleading than showing sample series names/values.
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
            List<KeywordTrendRow> rows = queryKeywordTrendRows(KEYWORD_TREND_SQL, Math.max(chartDays, SPIKE_LOOKBACK_DAYS));
            if (rows.isEmpty()) {
                return KeywordTrendPayload.empty();
            }

            List<KeywordTrendRow> displayedRows = selectDisplayedRows(rows);
            if (displayedRows.isEmpty()) {
                return KeywordTrendPayload.empty();
            }
            LinkedHashSet<LocalDate> chartPeriods = latestPeriods(rows, chartDays);
            List<KeywordTrendRow> chartRows = displayedRows.stream()
                    .filter(row -> chartPeriods.contains(row.period()))
                    .toList();
            if (chartRows.isEmpty()) {
                return KeywordTrendPayload.empty();
            }

            Map<Integer, SeriesMeta> seriesByRank = new LinkedHashMap<>();
            Map<LocalDate, Map<String, Object>> pointsByDate = new LinkedHashMap<>();
            LinkedHashSet<String> sourceNames = new LinkedHashSet<>();

            for (KeywordTrendRow row : chartRows) {
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
                    buildSpikeInsights(chartRows, rows, seriesByRank),
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
                            .addValue("allowedGroups", FIXED_GROUPS),
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
                rs.getString("source_name"),
                rs.getString("cause_analysis")
        );
    }

    private List<KeywordTrendRow> selectDisplayedRows(List<KeywordTrendRow> rows) {
        List<String> selectedGroups = new ArrayList<>(MAX_GROUPS);
        for (String fixedGroup : FIXED_GROUPS) {
            if (rows.stream().anyMatch(row -> fixedGroup.equals(row.groupName()))) {
                selectedGroups.add(fixedGroup);
            }
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

    private LinkedHashSet<LocalDate> latestPeriods(List<KeywordTrendRow> rows, int limit) {
        return rows.stream()
                .map(KeywordTrendRow::period)
                .distinct()
                .sorted(Comparator.reverseOrder())
                .limit(Math.max(1, limit))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private BigDecimal absOrZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.abs();
    }

    private boolean isSpikeRow(KeywordTrendRow row, List<KeywordTrendRow> lookbackRows) {
        if (row.prevRatio() == null || row.ratioDelta() == null) {
            return false;
        }

        BigDecimal absDelta = row.ratioDelta().abs();
        if (absDelta.compareTo(spikeDeltaThreshold.abs()) < 0) {
            return false;
        }

        List<BigDecimal> groupDeltas = lookbackRows.stream()
                .filter(candidate -> row.groupName().equals(candidate.groupName()))
                .map(KeywordTrendRow::ratioDelta)
                .filter(delta -> delta != null)
                .map(BigDecimal::abs)
                .filter(delta -> delta.compareTo(BigDecimal.ZERO) > 0)
                .toList();
        if (groupDeltas.isEmpty()) {
            return false;
        }

        BigDecimal maxDelta = groupDeltas.stream().max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
        if (absDelta.compareTo(maxDelta) >= 0) {
            return true;
        }

        BigDecimal averageDelta = groupDeltas.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(groupDeltas.size()), 4, RoundingMode.HALF_UP);
        return absDelta.compareTo(averageDelta.multiply(spikeAverageMultiplier)) >= 0;
    }

    private List<Map<String, Object>> buildSpikeInsights(
            List<KeywordTrendRow> rows,
            List<KeywordTrendRow> lookbackRows,
            Map<Integer, SeriesMeta> seriesByRank
    ) {
        return rows.stream()
                .filter(row -> row.groupRank() != null && row.groupRank() >= 1 && row.groupRank() <= MAX_GROUPS)
                .filter(row -> isSpikeRow(row, lookbackRows))
                .sorted(
                        Comparator.comparing(KeywordTrendRow::period)
                                .thenComparing(KeywordTrendRow::groupRank)
                )
                .map(row -> {
                    SeriesMeta series = seriesByRank.get(row.groupRank());
                    String key = series == null ? "trend" + row.groupRank() : series.key();
                    Map<String, Object> causeAnalysis = parseCauseAnalysis(row.causeAnalysisJson());
                    List<Map<String, Object>> evidence = buildDisplayEvidence(causeAnalysis);
                    if (evidence.isEmpty()) {
                        evidence = sanitizeFallbackEvidence(queryCauseEvidence(row.groupName(), row.period()), row.groupName());
                    }
                    List<Map<String, Object>> causeFactors = buildCauseFactors(causeAnalysis);
                    if (causeFactors.isEmpty()) {
                        causeFactors = buildEvidenceCauseFactors(row, evidence);
                    }
                    String directionLabel = row.ratioDelta().signum() < 0 ? "급락" : "급등";
                    String reason = buildCauseReason(row, directionLabel, causeAnalysis, evidence, causeFactors);
                    String skAxPoint = buildSkAxPoint(evidence);

                    Map<String, Object> insight = new LinkedHashMap<>();
                    insight.put("key", key);
                    insight.put("time", TREND_DATE_FORMATTER.format(row.period()));
                    insight.put("title", row.groupName() + " 관련 검색 관심도 " + directionLabel);
                    insight.put("valueLabel", formatRatioValue(row.ratio()) + " / " + formatDeltaValue(row.ratioDelta()));
                    insight.put("reason", reason);
                    insight.put("skAxPoint", skAxPoint);
                    insight.put("causeFactors", causeFactors);
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
                            .addValue("endDate", period)
            );
        } catch (DataAccessException ignored) {
            return List.of();
        }
    }

    private Map<String, Object> parseCauseAnalysis(String causeAnalysisJson) {
        if (causeAnalysisJson == null || causeAnalysisJson.isBlank() || "null".equals(causeAnalysisJson)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(causeAnalysisJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> causeDrivers(Map<String, Object> causeAnalysis) {
        Object drivers = causeAnalysis.get("keyword_drivers");
        if (!(drivers instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .toList();
    }

    private String buildCauseReason(
            KeywordTrendRow row,
            String directionLabel,
            Map<String, Object> causeAnalysis,
            List<Map<String, Object>> evidence,
            List<Map<String, Object>> causeFactors
    ) {
        List<Map<String, Object>> drivers = causeDrivers(causeAnalysis).stream()
                .filter(driver -> stringValue(driver.get("keyword")) != null)
                .limit(3)
                .toList();
        String evidenceCauseText = buildEvidenceCauseText(evidence);
        if (!drivers.isEmpty()) {
            String driverText = drivers.stream()
                    .map(this::formatDriver)
                    .filter(value -> !value.isBlank())
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("");
            if (evidenceCauseText != null) {
                return row.groupName() + " 관련 검색 관심도 " + directionLabel + "은 " + evidenceCauseText
                        + " 여기에 세부 키워드 " + driverText
                        + " 흐름이 겹치며 발생한 것으로 보입니다.";
            }
            return row.groupName() + " 관련 검색 관심도 " + directionLabel + "은 섹터 내 " + driverText
                    + " 흐름이 급등일 당일까지 집중되며 발생한 것으로 보입니다.";
        }

        if (evidenceCauseText != null) {
            return row.groupName() + " 관련 검색 관심도 " + directionLabel + "은 " + evidenceCauseText
                    + " 이 때문에 검색 수요가 커진 것으로 보입니다.";
        }

        if (evidence.isEmpty()) {
            return row.groupName() + " 관련 검색 관심도가 전일 대비 "
                    + formatDeltaValue(row.ratioDelta())
                    + " 변동했습니다. 아직 같은 기간에 바로 연결되는 출처가 충분하지 않습니다.";
        }
        return directionLabel + "일 당일까지 '" + row.groupName() + "' 관련 출처 "
                + evidence.size()
                + "건에서 같은 흐름이 확인됐습니다. 원문 링크로 실제 기사와 날짜를 확인해 주세요.";
    }

    private String buildEvidenceCauseText(List<Map<String, Object>> evidence) {
        List<String> titles = evidence.stream()
                .map(item -> stringValue(item.get("title")))
                .filter(title -> title != null)
                .map(this::cleanEvidenceTitle)
                .filter(title -> title != null)
                .distinct()
                .limit(2)
                .toList();
        if (titles.isEmpty()) {
            return null;
        }

        List<String> causes = inferCausePhrases(titles);
        if (causes.isEmpty()) {
            return "관련 기사들이 같은 기간 집중되면서";
        }
        return joinNaturalKorean(causes) + " 이슈가 같은 기간 겹치면서";
    }

    private List<String> inferCausePhrases(List<String> titles) {
        String text = String.join(" ", titles).toLowerCase();
        List<String> causes = new ArrayList<>();
        addCauseIfMatches(causes, text, "정부 GPU 사업 수주와 AI 인프라 구축", "정부", "gpu", "수주");
        addCauseIfMatches(causes, text, "공공 사업 수주·공급 계약", "공공", "수주", "계약", "공급", "조달", "우선협상");
        addCauseIfMatches(causes, text, "오픈AI 활용 사례와 대규모 토큰 처리 성과", "오픈ai", "토큰");
        addCauseIfMatches(causes, text, "대기업 SI사의 AI 연구개발 투자 확대", "연구개발비", "r&d", "연구개발");
        addCauseIfMatches(causes, text, "AI 개발 자동화 솔루션 공개", "코딩", "검증", "aind", "공개");
        addCauseIfMatches(causes, text, "AI/AX 전환과 생성형 AI 적용 확대", "생성형", "ai", "ax", "llm", "에이전트", "디지털 전환", "dx");
        addCauseIfMatches(causes, text, "컨퍼런스와 공식 발표", "컨퍼런스", "세미나", "행사", "발표", "개최", "m.ax");
        addCauseIfMatches(causes, text, "보안 사고와 대응 솔루션 이슈", "보안", "랜섬웨어", "해킹", "제로트러스트", "edr", "xdr", "soc");
        return causes.stream().distinct().limit(3).toList();
    }

    private void addCauseIfMatches(List<String> causes, String text, String cause, String... tokens) {
        for (String token : tokens) {
            if (text.contains(token.toLowerCase())) {
                causes.add(cause);
                return;
            }
        }
    }

    private String joinNaturalKorean(List<String> items) {
        if (items.isEmpty()) {
            return "";
        }
        if (items.size() == 1) {
            return items.get(0);
        }
        if (items.size() == 2) {
            return items.get(0) + ", " + items.get(1);
        }
        return items.get(0) + ", " + items.get(1) + ", " + items.get(2);
    }

    private String buildSkAxPoint(List<Map<String, Object>> evidence) {
        if (evidence.isEmpty()) {
            return "";
        }
        return "";
    }

    private String formatDriver(Map<String, Object> driver) {
        String keyword = stringValue(driver.get("keyword"));
        if (keyword == null) {
            return "";
        }
        return "'" + keyword + "' 관련";
    }

    private List<Map<String, Object>> buildCauseFactors(Map<String, Object> causeAnalysis) {
        List<Map<String, Object>> factors = new ArrayList<>();
        int rank = 1;
        for (Map<String, Object> driver : causeDrivers(causeAnalysis)) {
            String keyword = stringValue(driver.get("keyword"));
            if (keyword == null) {
                continue;
            }
            int evidenceCount = intValue(driver.get("evidence_count"));
            int windowCount = intValue(driver.get("raw_article_window_count"));
            int baselineCount = intValue(driver.get("raw_article_baseline_count"));
            String lift = stringValue(driver.get("raw_article_lift"));

            Map<String, Object> factor = new LinkedHashMap<>();
            factor.put("rank", rank);
            factor.put("keyword", keyword);
            factor.put("title", keyword + " 관련 언급 집중");
            factor.put("description", buildCauseFactorDescription(keyword, windowCount, baselineCount, lift, evidenceCount));
            factor.put("evidenceCount", evidenceCount);
            factors.add(factor);
            rank++;
            if (factors.size() >= 3) {
                break;
            }
        }
        return factors;
    }

    private String buildCauseFactorDescription(
            String keyword,
            int windowCount,
            int baselineCount,
            String lift,
            int evidenceCount
    ) {
        List<String> pieces = new ArrayList<>();
        if (windowCount > 0) {
            pieces.add(keyword + " 관련 흐름이 급등일 당일까지 확인됨");
        }
        if (evidenceCount > 0) {
            pieces.add("검증 원문 연결");
        }
        if (pieces.isEmpty()) {
            return keyword + " 관련 흐름이 같은 기간 원인 후보로 감지됐습니다.";
        }
        return String.join(" · ", pieces);
    }

    private List<Map<String, Object>> buildEvidenceCauseFactors(KeywordTrendRow row, List<Map<String, Object>> evidence) {
        List<Map<String, Object>> factors = new ArrayList<>();
        int rank = 1;
        for (Map<String, Object> item : evidence) {
            String title = stringValue(item.get("title"));
            if (title == null) {
                continue;
            }
            Map<String, Object> factor = new LinkedHashMap<>();
            factor.put("rank", rank);
            factor.put("keyword", row.groupName());
            factor.put("title", trimSentence(cleanEvidenceTitle(title), 54));
            factor.put("description", row.groupName() + " 검색 급등일 당일까지 이 제목의 원문이 수집돼 원인 후보로 연결됐습니다.");
            factor.put("evidenceCount", 1);
            factors.add(factor);
            rank++;
            if (factors.size() >= 3) {
                break;
            }
        }
        return factors;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildDisplayEvidence(Map<String, Object> causeAnalysis) {
        List<Map<String, Object>> displayed = new ArrayList<>();
        for (Map<String, Object> driver : causeDrivers(causeAnalysis)) {
            Object evidence = driver.get("evidence");
            if (!(evidence instanceof Map<?, ?> evidenceMap)) {
                continue;
            }
            String driverKeyword = stringValue(driver.get("keyword"));
            addDisplayEvidence(displayed, evidenceMap.get("raw_articles"), "기사/원문", driverKeyword);
            addDisplayEvidence(displayed, evidenceMap.get("card_news"), "카드뉴스", driverKeyword);
            addDisplayEvidence(displayed, evidenceMap.get("integrated_issues"), "통합 이슈", driverKeyword);
            addDisplayEvidence(displayed, evidenceMap.get("raw_article_business_signals"), "비즈니스 신호", driverKeyword);
            if (displayed.size() >= 3) {
                break;
            }
        }
        return displayed.stream().limit(3).toList();
    }

    @SuppressWarnings("unchecked")
    private void addDisplayEvidence(List<Map<String, Object>> target, Object itemsValue, String label, String driverKeyword) {
        if (!(itemsValue instanceof List<?> items) || target.size() >= 3) {
            return;
        }
        for (Object item : items) {
            if (target.size() >= 3) {
                return;
            }
            if (!(item instanceof Map<?, ?> rawMap)) {
                continue;
            }
            Map<String, Object> source = (Map<String, Object>) rawMap;
            Map<String, Object> resolvedSource = resolveEvidenceSource(label, source);
            String url = normalizeExternalUrl(firstPresent(resolvedSource, "url", "source_url"));
            if (url == null) {
                continue;
            }
            Map<String, Object> display = new LinkedHashMap<>();
            display.put("label", label);
            display.put("source", firstPresent(resolvedSource, "source_name", "source", "peer_id"));
            display.put("title", cleanEvidenceTitle(stringValue(firstPresent(
                    resolvedSource,
                    "title",
                    "headline",
                    "summary",
                    "evidence_text",
                    "one_line_summary"
            ))));
            display.put("basis", buildEvidenceSummary(label, source, driverKeyword));
            display.put("url", url);
            display.put("publishedAt", firstPresent(resolvedSource, "published_at", "created_at", "collected_at"));
            target.add(display);
        }
    }

    private List<Map<String, Object>> sanitizeFallbackEvidence(List<Map<String, Object>> evidence, String keyword) {
        return evidence.stream()
                .filter(item -> normalizeExternalUrl(item.get("url")) != null)
                .limit(3)
                .map(item -> {
                    Map<String, Object> display = new LinkedHashMap<>();
                    display.put("label", "기사/원문");
                    display.put("source", item.get("source_name"));
                    display.put("title", cleanEvidenceTitle(stringValue(item.get("title"))));
                    display.put("basis", "'" + keyword + "' 검색 급등일 당일까지 이 원문에서 관련 언급이 확인됐습니다.");
                    display.put("url", normalizeExternalUrl(item.get("url")));
                    display.put("publishedAt", item.get("published_at"));
                    return display;
                })
                .toList();
    }

    private String buildEvidenceSummary(String label, Map<String, Object> source, String driverKeyword) {
        String keyword = driverKeyword == null ? "관련 키워드" : "'" + driverKeyword + "'";
        if ("비즈니스 신호".equals(label)) {
            String signalType = stringValue(source.get("signal_type"));
            String businessArea = stringValue(source.get("business_area"));
            if (signalType != null && businessArea != null) {
                return keyword + "와 연결된 " + businessArea + "/" + signalType + " 신호입니다.";
            }
            String summary = stringValue(source.get("summary"));
            if (summary != null) {
                return trimSentence(summary, 96);
            }
            return keyword + "와 연결된 비즈니스 신호가 확인됐습니다.";
        }
        if ("통합 이슈".equals(label)) {
            String summary = stringValue(firstPresent(source, "one_line_summary", "headline"));
            if (summary != null) {
                return keyword + " 관련 통합 이슈 근거입니다.";
            }
            return keyword + " 관련 복수 출처가 하나의 이슈로 묶여 확인됐습니다.";
        }
        if ("카드뉴스".equals(label)) {
            String eventType = stringValue(source.get("event_type"));
            if (eventType != null) {
                return keyword + " 관련 " + eventType + " 이슈가 카드뉴스 근거로 확인됐습니다.";
            }
            return keyword + " 관련 카드뉴스 근거가 확인됐습니다.";
        }
        return keyword + " 관련 원문 기사에서 같은 기간 언급이 확인됐습니다.";
    }

    private Map<String, Object> resolveEvidenceSource(String label, Map<String, Object> source) {
        if ("기사/원문".equals(label)) {
            return source;
        }

        Map<String, Object> linkedSource = switch (label) {
            case "통합 이슈" -> queryIntegratedIssueSource(firstPresent(source, "id", "integrated_issue_id"));
            case "카드뉴스" -> queryCardNewsSource(firstPresent(source, "id", "card_news_id"));
            case "비즈니스 신호" -> queryBusinessSignalSource(
                    firstPresent(source, "raw_article_id"),
                    firstPresent(source, "id", "signal_id")
            );
            default -> Map.of();
        };

        if (linkedSource.isEmpty()) {
            return source;
        }

        Map<String, Object> merged = new LinkedHashMap<>(source);
        linkedSource.forEach((key, value) -> {
            if (value != null && !String.valueOf(value).isBlank()) {
                merged.put(key, value);
            }
        });
        return merged;
    }

    private Map<String, Object> queryIntegratedIssueSource(Object issueIdValue) {
        String issueId = stringValue(issueIdValue);
        if (issueId == null) {
            return Map.of();
        }

        Map<String, Object> sourceArticle = querySingleEvidenceSource(INTEGRATED_ISSUE_SOURCE_ARTICLE_SQL, "issueId", issueId);
        if (!sourceArticle.isEmpty()) {
            return sourceArticle;
        }

        return querySingleEvidenceSource(INTEGRATED_ISSUE_REPRESENTATIVE_SQL, "issueId", issueId);
    }

    private Map<String, Object> queryCardNewsSource(Object cardNewsIdValue) {
        String cardNewsId = stringValue(cardNewsIdValue);
        if (cardNewsId == null) {
            return Map.of();
        }

        return querySingleEvidenceSource(CARD_NEWS_SOURCE_SQL, "cardNewsId", cardNewsId);
    }

    private Map<String, Object> queryBusinessSignalSource(Object rawArticleIdValue, Object signalIdValue) {
        String rawArticleId = stringValue(rawArticleIdValue);
        if (rawArticleId != null) {
            Map<String, Object> rawArticle = querySingleEvidenceSource(
                    BUSINESS_SIGNAL_SOURCE_BY_RAW_ARTICLE_SQL,
                    "rawArticleId",
                    rawArticleId
            );
            if (!rawArticle.isEmpty()) {
                return rawArticle;
            }
        }

        String signalId = stringValue(signalIdValue);
        if (signalId == null) {
            return Map.of();
        }

        return querySingleEvidenceSource(BUSINESS_SIGNAL_SOURCE_SQL, "signalId", signalId);
    }

    private Map<String, Object> querySingleEvidenceSource(String sql, String paramName, String paramValue) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    sql,
                    new MapSqlParameterSource().addValue(paramName, paramValue)
            );
            if (rows.isEmpty()) {
                return Map.of();
            }
            return rows.get(0);
        } catch (DataAccessException ignored) {
            return Map.of();
        }
    }

    private String normalizeExternalUrl(Object value) {
        if (value == null) {
            return null;
        }
        String url = String.valueOf(value).trim();
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        return null;
    }

    private Object firstPresent(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            Object value = source.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return String.valueOf(value);
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return new BigDecimal(text).intValue();
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private String formatLiftValue(String value) {
        try {
            return new BigDecimal(value).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException ignored) {
            return value;
        }
    }

    private String trimSentence(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 1)).trim() + "...";
    }

    private String cleanEvidenceTitle(String value) {
        if (value == null) {
            return null;
        }
        String title = value.replaceAll("\\s+", " ").trim();
        if (title.isBlank()) {
            return null;
        }
        return trimSentence(title, 120);
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
            String sourceName,
            String causeAnalysisJson
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
                    sourceName,
                    causeAnalysisJson
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
