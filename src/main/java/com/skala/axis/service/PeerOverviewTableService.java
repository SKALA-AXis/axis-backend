package com.skala.axis.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class PeerOverviewTableService {
    private static final List<String> FINANCIAL_PEER_IDS = List.of(
            "sk_ax",
            "samsung_sds",
            "lg_cns",
            "hyundai_autoever",
            "posco_dx"
    );

    private static final List<DisplayPeer> DISPLAY_PEERS = List.of(
            new DisplayPeer(0, "sk_ax", "SK AX"),
            new DisplayPeer(1, "samsung_sds", "삼성 SDS"),
            new DisplayPeer(2, "lg_cns", "LG CNS"),
            new DisplayPeer(3, "hyundai_autoever", "현대 오토에버"),
            new DisplayPeer(4, "posco_dx", "포스코 DX")
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Value("${axis.peer-overview.table-cache-ttl-seconds:600}")
    private long tableCacheTtlSeconds;

    private volatile CachedPeerOverviewTable cachedPeerOverviewTable = new CachedPeerOverviewTable(Map.of(), Instant.EPOCH);

    public Map<String, Object> getPeerOverviewTable() {
        CachedPeerOverviewTable current = cachedPeerOverviewTable;
        if (!isPeerOverviewTableCacheExpired(current.cachedAt())) {
            return current.payload();
        }

        synchronized (this) {
            current = cachedPeerOverviewTable;
            if (!isPeerOverviewTableCacheExpired(current.cachedAt())) {
                return current.payload();
            }

            Map<String, Object> refreshed = loadPeerOverviewTable();
            cachedPeerOverviewTable = new CachedPeerOverviewTable(refreshed, Instant.now());
            return refreshed;
        }
    }

    private Map<String, Object> loadPeerOverviewTable() {
        String period = resolveCommonPeriod();
        Map<String, SupplementalRow> supplementalRows = loadSupplementalRows();
        List<Map<String, Object>> rows = loadRows(period, supplementalRows);
        return mapOf(
                "periodLabel", period,
                "coverageLabel", period == null ? "공통 분기 미확보" : "SK AX · 삼성 SDS · LG CNS · 현대 오토에버 · 포스코 DX 공통 분기 기준",
                "financialSourceLabel", "각 사 IR·사업보고서 기반",
                "supplementalSourceLabel", "기업 프로필·최근 카드뉴스 기반 보조 지표, 미확보 시 -",
                "comparisonInsights", buildComparisonInsights(rows),
                "swotInsights", loadSwotInsights(),
                "rows", rows
        );
    }

    private boolean isPeerOverviewTableCacheExpired(Instant cachedAt) {
        if (cachedAt == null || Instant.EPOCH.equals(cachedAt)) {
            return true;
        }
        if (tableCacheTtlSeconds <= 0) {
            return true;
        }
        return Duration.between(cachedAt, Instant.now()).getSeconds() >= tableCacheTtlSeconds;
    }

    public Map<String, Object> getPeerPositioningChart() {
        String period = resolvePositioningCommonPeriod();
        boolean mixedPeriods = period == null;
        List<Map<String, Object>> points = mixedPeriods ? loadLatestPositioningPoints() : loadPositioningPoints(period);
        return mapOf(
                "periodLabel", mixedPeriods ? "peer별 최신 분기" : period,
                "coverageLabel", mixedPeriods
                        ? "매출 + 매출 YoY 공통 분기가 없어 peer별 최신 가용 분기 기준으로 표시"
                        : "SK AX · 삼성 SDS · LG CNS · 현대 오토에버 · 포스코 DX 공통 분기 기준",
                "financialSourceLabel", "각 사 IR·사업보고서 기반",
                "xAxisLabel", "사업 규모 (매출, 억원)",
                "yAxisLabel", "매출 성장률 (YoY, %)",
                "referenceRevenueKrwBn", 30000,
                "referenceGrowthPct", 5,
                "points", points
        );
    }

    private String resolveCommonPeriod() {
        String sql = """
                WITH metric_rows AS (
                    SELECT
                        id AS metric_row_id,
                        peer_id,
                        period,
                        CASE
                            WHEN COALESCE(NULLIF(metric_label, ''), metric_name) IN ('매출', '매출액', '총매출', 'revenue_total', 'revenue') THEN 'revenue_total'
                            WHEN COALESCE(NULLIF(metric_label, ''), metric_name) IN ('영업이익', 'operating_profit', 'operating_income') THEN 'operating_profit'
                            WHEN COALESCE(NULLIF(metric_label, ''), metric_name) IN ('영업이익률', 'operating_margin') THEN 'operating_margin'
                            WHEN COALESCE(NULLIF(metric_label, ''), metric_name) IN ('순이익', '당기순이익', 'net_income', 'net_profit') THEN 'net_income'
                            ELSE COALESCE(NULLIF(metric_label, ''), metric_name)
                        END AS metric_name_canonical,
                        COALESCE(value_krwbn, value_numeric::double precision) AS metric_value,
                        ROW_NUMBER() OVER (
                            PARTITION BY peer_id, period,
                                CASE
                                    WHEN COALESCE(NULLIF(metric_label, ''), metric_name) IN ('매출', '매출액', '총매출', 'revenue_total', 'revenue') THEN 'revenue_total'
                                    WHEN COALESCE(NULLIF(metric_label, ''), metric_name) IN ('영업이익', 'operating_profit', 'operating_income') THEN 'operating_profit'
                                    WHEN COALESCE(NULLIF(metric_label, ''), metric_name) IN ('영업이익률', 'operating_margin') THEN 'operating_margin'
                                    WHEN COALESCE(NULLIF(metric_label, ''), metric_name) IN ('순이익', '당기순이익', 'net_income', 'net_profit') THEN 'net_income'
                                    ELSE COALESCE(NULLIF(metric_label, ''), metric_name)
                                END
                            ORDER BY
                                confidence DESC NULLS LAST,
                                updated_at DESC NULLS LAST,
                                id DESC
                        ) AS row_rank
                    FROM raw_article_financial_metrics
                    WHERE metric_scope = 'company_total'
                      AND source_type = 'ir'
                      AND peer_id IN (?, ?, ?, ?, ?)
                      AND period IS NOT NULL
                      AND (
                          COALESCE(NULLIF(metric_label, ''), metric_name) NOT IN ('순이익', '당기순이익', 'net_income', 'net_profit')
                          OR business_area = 'company_total'
                      )
                ),
                latest_metric_rows AS (
                    SELECT *
                    FROM metric_rows
                    WHERE row_rank = 1
                ),
                period_coverage AS (
                    SELECT
                        peer_id,
                        period,
                        MAX(CASE WHEN metric_name_canonical = 'revenue_total' THEN metric_value END) AS revenue_total,
                        MAX(CASE WHEN metric_name_canonical = 'operating_profit' THEN metric_value END) AS operating_profit
                    FROM latest_metric_rows
                    GROUP BY peer_id, period
                )
                SELECT period
                FROM period_coverage
                WHERE revenue_total IS NOT NULL
                  AND operating_profit IS NOT NULL
                GROUP BY period
                HAVING COUNT(DISTINCT peer_id) = ?
                ORDER BY
                    MAX(COALESCE(NULLIF(SUBSTRING(period FROM '^([0-9]{4})'), '')::int, 0)) DESC,
                    MAX(COALESCE(NULLIF(SUBSTRING(period FROM 'Q([1-4])$'), '')::int, 0)) DESC,
                    period DESC
                LIMIT 1
                """;

        return jdbcTemplate.query(
                sql,
                ps -> {
                    bindFinancialPeerIds(ps, 1);
                    ps.setInt(FINANCIAL_PEER_IDS.size() + 1, FINANCIAL_PEER_IDS.size());
                },
                rs -> rs.next() ? rs.getString("period") : null
        );
    }

    private String resolvePositioningCommonPeriod() {
        String sql = """
                WITH source_rows AS (
                    SELECT
                        peer_id,
                        period,
                        metric_name,
                        value_krwbn,
                        CASE
                            WHEN metric_name <> 'revenue_total_yoy' THEN value_numeric
                            WHEN value_numeric IS NULL THEN NULL
                            WHEN ABS(value_numeric) <= 200 THEN value_numeric
                            WHEN regexp_match(COALESCE(evidence_text, ''), '(?i)YoY\\s*([△▲+\\-]?)\\s*([0-9]+(?:\\.[0-9]+)?)%') IS NOT NULL THEN
                                (
                                    CASE
                                        WHEN (regexp_match(COALESCE(evidence_text, ''), '(?i)YoY\\s*([△▲+\\-]?)\\s*([0-9]+(?:\\.[0-9]+)?)%'))[1] IN ('△', '-') THEN -1
                                        ELSE 1
                                    END
                                ) * ((regexp_match(COALESCE(evidence_text, ''), '(?i)YoY\\s*([△▲+\\-]?)\\s*([0-9]+(?:\\.[0-9]+)?)%'))[2])::numeric
                            ELSE NULL
                        END AS normalized_value_numeric,
                        source_type,
                        confidence,
                        updated_at,
                        id,
                        evidence_text
                    FROM raw_article_financial_metrics
                    WHERE metric_scope = 'company_total'
                      AND business_area = 'company_total'
                      AND source_type = 'ir'
                      AND peer_id IN (?, ?, ?, ?, ?)
                      AND period ~ '^[0-9]{4}Q[1-4]$'
                      AND metric_name IN ('revenue_total', 'revenue_total_yoy')
                ),
                metric_rows AS (
                    SELECT
                        peer_id,
                        period,
                        metric_name,
                        value_krwbn,
                        normalized_value_numeric,
                        ROW_NUMBER() OVER (
                            PARTITION BY peer_id, period, metric_name
                            ORDER BY
                                CASE
                                    WHEN metric_name = 'revenue_total_yoy' AND normalized_value_numeric IS NULL THEN 1
                                    ELSE 0
                                END,
                                confidence DESC NULLS LAST,
                                updated_at DESC NULLS LAST,
                                id DESC
                        ) AS row_rank
                    FROM source_rows
                ),
                latest_metric_rows AS (
                    SELECT *
                    FROM metric_rows
                    WHERE row_rank = 1
                ),
                revenue_periods AS (
                    SELECT
                        peer_id,
                        period,
                        MAX(CASE WHEN metric_name = 'revenue_total' THEN value_krwbn END) AS revenue_total_krwbn,
                        MAX(CASE WHEN metric_name = 'revenue_total_yoy' THEN normalized_value_numeric END) AS revenue_total_yoy
                    FROM latest_metric_rows
                    GROUP BY peer_id, period
                ),
                period_coverage AS (
                    SELECT
                        current_rows.peer_id,
                        current_rows.period,
                        current_rows.revenue_total_krwbn,
                        COALESCE(
                            current_rows.revenue_total_yoy,
                            CASE
                                WHEN previous_rows.revenue_total_krwbn IS NOT NULL
                                 AND previous_rows.revenue_total_krwbn <> 0
                                 AND current_rows.revenue_total_krwbn IS NOT NULL
                                THEN ((current_rows.revenue_total_krwbn - previous_rows.revenue_total_krwbn)
                                    / previous_rows.revenue_total_krwbn * 100)
                                ELSE NULL
                            END
                        ) AS revenue_total_yoy
                    FROM revenue_periods current_rows
                    LEFT JOIN revenue_periods previous_rows
                      ON previous_rows.peer_id = current_rows.peer_id
                     AND previous_rows.period = (
                         (COALESCE(NULLIF(SUBSTRING(current_rows.period FROM '^([0-9]{4})'), '')::int, 0) - 1)::text
                         || 'Q'
                         || COALESCE(NULLIF(SUBSTRING(current_rows.period FROM 'Q([1-4])$'), ''), '')
                     )
                )
                SELECT period
                FROM period_coverage
                WHERE revenue_total_krwbn IS NOT NULL
                  AND revenue_total_yoy IS NOT NULL
                GROUP BY period
                HAVING COUNT(DISTINCT peer_id) = ?
                ORDER BY
                    MAX(COALESCE(NULLIF(SUBSTRING(period FROM '^([0-9]{4})'), '')::int, 0)) DESC,
                    MAX(COALESCE(NULLIF(SUBSTRING(period FROM 'Q([1-4])$'), '')::int, 0)) DESC,
                    period DESC
                LIMIT 1
                """;

        return jdbcTemplate.query(
                sql,
                ps -> {
                    bindFinancialPeerIds(ps, 1);
                    ps.setInt(FINANCIAL_PEER_IDS.size() + 1, FINANCIAL_PEER_IDS.size());
                },
                rs -> rs.next() ? rs.getString("period") : null
        );
    }

    private List<Map<String, Object>> loadRows(String period, Map<String, SupplementalRow> supplementalRows) {
        List<Map<String, Object>> financialRows = loadFinancialRows(period);
        Map<String, Map<String, Object>> financialRowById = new HashMap<>();
        for (Map<String, Object> row : financialRows) {
            financialRowById.put((String) row.get("id"), row);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (DisplayPeer displayPeer : DISPLAY_PEERS) {
            Map<String, Object> baseRow = new LinkedHashMap<>();
            baseRow.put("id", displayPeer.id());
            baseRow.put("label", displayPeer.label());
            baseRow.put("revenueKrwBn", null);
            baseRow.put("revenueQoqPct", null);
            baseRow.put("operatingProfitKrwBn", null);
            baseRow.put("operatingProfitQoqPct", null);
            baseRow.put("netIncomeKrwBn", null);
            baseRow.put("operatingMarginPct", null);
            baseRow.put("operatingMarginQoqDeltaPctp", null);
            baseRow.put("axRevenueSharePct", null);
            baseRow.put("topKeyword", null);
            baseRow.put("businessKeyword", null);
            baseRow.put("technologyKeyword", null);
            baseRow.put("topKeywordReason", null);
            baseRow.put("topKeywordBasis", null);
            baseRow.put("topKeywordScore", null);
            baseRow.put("topKeywordEvidence", List.of());
            baseRow.put("topKeywordEvidenceUrls", List.of());
            baseRow.put("dartRceptNo", null);

            Map<String, Object> financialRow = financialRowById.get(displayPeer.id());
            if (financialRow != null) {
                baseRow.putAll(financialRow);
            }

            SupplementalRow supplementalRow = supplementalRows.get(displayPeer.id());
            if (supplementalRow != null) {
                if (baseRow.get("label") == null) {
                    baseRow.put("label", supplementalRow.label());
                }
                baseRow.put("axRevenueSharePct", supplementalRow.axRevenueSharePct());
                baseRow.put("topKeyword", supplementalRow.topKeyword());
                baseRow.put("businessKeyword", supplementalRow.businessKeyword());
                baseRow.put("technologyKeyword", supplementalRow.technologyKeyword());
                baseRow.put("topKeywordReason", supplementalRow.topKeywordReason());
                baseRow.put("topKeywordBasis", supplementalRow.topKeywordBasis());
                baseRow.put("topKeywordScore", supplementalRow.topKeywordScore());
                baseRow.put("topKeywordEvidence", supplementalRow.topKeywordEvidence());
                baseRow.put("topKeywordEvidenceUrls", supplementalRow.topKeywordEvidenceUrls());
            }

            rows.add(baseRow);
        }
        return rows;
    }

    private Map<String, List<Map<String, String>>> loadSwotInsights() {
        try {
            String sql = """
                    WITH target_peers(id, name, aliases) AS (
                        VALUES
                            ('sk_ax', 'SK AX', ARRAY['sk ax', 'skax', '에스케이 에이엑스']),
                            ('samsung_sds', '삼성 SDS', ARRAY['samsung sds', 'samsungsds', '삼성 sds', '삼성sds']),
                            ('lg_cns', 'LG CNS', ARRAY['lg cns', 'lgcns', '엘지 cns', '엘지씨엔에스']),
                            ('hyundai_autoever', '현대 오토에버', ARRAY['hyundai autoever', 'hyundai_autoever', '현대 오토에버', '현대오토에버']),
                            ('posco_dx', '포스코 DX', ARRAY['posco dx', 'posco_dx', '포스코 dx', '포스코dx'])
                    ),
                    matched_articles AS (
                        SELECT
                            tp.id AS peer_id,
                            tp.name AS peer_name,
                            ra.title,
                            ra.content,
	                            ra.metadata::text AS metadata_text,
	                            COALESCE(ra.published_at, ra.collected_at, ra.created_at) AS article_at,
	                            ROW_NUMBER() OVER (
	                                PARTITION BY tp.id
	                                ORDER BY
	                                    CASE
	                                        WHEN COALESCE(ra.content, '') ILIKE '%SWOT 분석%' THEN 0
	                                        ELSE 1
	                                    END,
	                                    CASE
	                                        WHEN COALESCE(ra.content, '') LIKE '%입사제안 받기%' THEN 1
	                                        ELSE 0
	                                    END,
	                                    COALESCE(ra.published_at, ra.collected_at, ra.created_at) DESC NULLS LAST,
	                                    ra.id DESC
	                            ) AS row_rank
	                        FROM raw_articles ra
	                        JOIN target_peers tp
	                          ON EXISTS (
	                              SELECT 1
	                              FROM unnest(tp.aliases) alias
	                              WHERE lower(
	                                  COALESCE(ra.title, '')
	                                  || ' '
	                                  || split_part(trim(BOTH ' "' FROM COALESCE(ra.content, '')), E'\n', 1)
	                              ) LIKE '%' || alias || '%'
	                          )
	                        WHERE ra.source_name = 'catch_company_analysis'
	                          AND COALESCE(ra.content, '') ILIKE '%SWOT 분석%'
	                          AND COALESCE(ra.content, '') NOT LIKE '%입사제안 받기%'
	                    )
                    SELECT peer_id, peer_name, title, content, metadata_text
                    FROM matched_articles
                    WHERE row_rank = 1
                    """;

            Map<String, List<Map<String, String>>> insights = jdbcTemplate.query(sql, rs -> {
                Map<String, List<Map<String, String>>> result = new LinkedHashMap<>();
                while (rs.next()) {
                    String peerId = rs.getString("peer_id");
                    String peerName = rs.getString("peer_name");
                    String sourceText = String.join("\n",
                            nullToEmpty(rs.getString("title")),
                            nullToEmpty(rs.getString("content")),
                            nullToEmpty(rs.getString("metadata_text"))
                    );
                    List<Map<String, String>> items = parseSwotItems(peerName, sourceText);
                    if (!items.isEmpty()) {
                        result.put(peerId, items);
                    }
                }
                return result;
            });

            for (DisplayPeer displayPeer : DISPLAY_PEERS) {
                insights.putIfAbsent(displayPeer.id(), missingSwotInsights());
            }
            insights.put("all", missingSwotInsights());
            return insights;
        } catch (DataAccessException ex) {
            log.warn("PeerOverviewTable | SWOT insights unavailable", ex);
            Map<String, List<Map<String, String>>> fallback = new LinkedHashMap<>();
            for (DisplayPeer displayPeer : DISPLAY_PEERS) {
                fallback.put(displayPeer.id(), missingSwotInsights());
            }
            fallback.put("all", missingSwotInsights());
            return fallback;
        }
    }

    private List<Map<String, String>> parseSwotItems(String peerName, String sourceText) {
        Map<String, String> parsed = new LinkedHashMap<>();
        parsed.putAll(parseSwotFromJson(sourceText));
        parsed.putAll(parseSwotFromSections(sourceText));

        List<Map<String, String>> items = new ArrayList<>();
        addSwotItem(items, "Strength", peerName, parsed.get("Strength"));
        addSwotItem(items, "Weakness", peerName, parsed.get("Weakness"));
        addSwotItem(items, "Opportunity", peerName, parsed.get("Opportunity"));
        addSwotItem(items, "Threat", peerName, parsed.get("Threat"));
        return items;
    }

    private Map<String, String> parseSwotFromJson(String sourceText) {
        Map<String, String> result = new LinkedHashMap<>();
        int jsonStart = sourceText.indexOf('{');
        int jsonEnd = sourceText.lastIndexOf('}');
        if (jsonStart < 0 || jsonEnd <= jsonStart) {
            return result;
        }

        String jsonText = sourceText.substring(jsonStart, jsonEnd + 1);
        try {
            Map<String, Object> root = objectMapper.readValue(jsonText, new TypeReference<>() {
            });
            collectSwotJsonValues(root, result);
        } catch (Exception ignored) {
            // Fall back to section parsing below.
        }
        return result;
    }

    private void collectSwotJsonValues(Object value, Map<String, String> result) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String canonical = canonicalSwotLabel(String.valueOf(entry.getKey()));
                if (canonical != null) {
                    String text = flattenSwotValue(entry.getValue());
                    if (!text.isBlank()) {
                        result.putIfAbsent(canonical, text);
                    }
                }
                collectSwotJsonValues(entry.getValue(), result);
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                collectSwotJsonValues(item, result);
            }
        }
    }

    private String flattenSwotValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String stringValue) {
            return normalizeDisplayText(stringValue);
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(this::flattenSwotValue)
                    .filter(text -> !text.isBlank())
                    .reduce((left, right) -> left + " " + right)
                    .orElse("");
        }
        if (value instanceof Map<?, ?> map) {
            return map.values().stream()
                    .map(this::flattenSwotValue)
                    .filter(text -> !text.isBlank())
                    .reduce((left, right) -> left + " " + right)
                    .orElse("");
        }
        return normalizeDisplayText(String.valueOf(value));
    }

    private Map<String, String> parseSwotFromSections(String sourceText) {
        Map<String, String> result = new LinkedHashMap<>();
        parseSwotLines(extractSwotAnalysisSection(sourceText), result);
        parseSwotLines(sourceText, result);
        return result;
    }

    private String extractSwotAnalysisSection(String sourceText) {
        Pattern sectionPattern = Pattern.compile("(?is)(?:^|\\R)\\s*#{1,6}\\s*SWOT\\s*분석\\s*\\R(.*?)(?=\\R\\s*#{1,6}\\s+|$)");
        Matcher sectionMatcher = sectionPattern.matcher(sourceText);
        return sectionMatcher.find() ? sectionMatcher.group(1) : "";
    }

    private void parseSwotLines(String sourceText, Map<String, String> result) {
        if (sourceText == null || sourceText.isBlank()) {
            return;
        }
        String labels = "Strength|Weakness|Opportunity|Threat|강점|약점|기회|위협";
        Pattern pattern = Pattern.compile(
                "(?is)(?:^|[\\n\\r\\{,])\\s*(?:[-*•]\\s*)?(Strength|Weakness|Opportunity|Threat|강점|약점|기회|위협)\\s*[\\]:：\\-]\\s*(.*?)(?=(?:[\\n\\r\\{,])\\s*(?:[-*•]\\s*)?(?:"
                        + labels
                        + ")\\s*[\\]:：\\-]|(?:[\\n\\r])\\s*#{1,6}\\s+|$)"
        );
        Matcher matcher = pattern.matcher(sourceText);
        while (matcher.find()) {
            String canonical = canonicalSwotLabel(matcher.group(1));
            String body = normalizeDisplayText(matcher.group(2));
            if (canonical != null && !body.isBlank()) {
                result.putIfAbsent(canonical, body);
            }
        }
    }

    private void addSwotItem(List<Map<String, String>> items, String label, String peerName, String body) {
        if (body == null || body.isBlank()) {
            return;
        }
        items.add(insight(label, body));
    }

    private List<Map<String, String>> missingSwotInsights() {
        return List.of(
                insight("Strength", "정보 없음"),
                insight("Weakness", "정보 없음"),
                insight("Opportunity", "정보 없음"),
                insight("Threat", "정보 없음")
        );
    }

    private String canonicalSwotLabel(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        return switch (normalized) {
            case "strength", "strengths", "강점" -> "Strength";
            case "weakness", "weaknesses", "약점" -> "Weakness";
            case "opportunity", "opportunities", "기회" -> "Opportunity";
            case "threat", "threats", "위협" -> "Threat";
            default -> null;
        };
    }

    private String normalizeDisplayText(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value
                .replaceAll("(?is)<[^>]+>", " ")
                .replaceAll("[\\[\\]\\{\\}\"]", " ")
                .replaceAll("(?m)^\\s*[-*•]\\s*", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.length() > 260) {
            int sentenceEnd = Math.max(cleaned.lastIndexOf(". ", 220), cleaned.lastIndexOf("다. ", 220));
            int cutIndex = sentenceEnd > 80 ? sentenceEnd + 1 : 260;
            cleaned = cleaned.substring(0, Math.min(cutIndex, cleaned.length())).trim();
        }
        return cleaned;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private Map<String, List<Map<String, String>>> buildComparisonInsights(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> rowById = new HashMap<>();
        for (Map<String, Object> row : rows) {
            rowById.put((String) row.get("id"), row);
        }

        Map<String, List<Map<String, String>>> insights = new LinkedHashMap<>();
        insights.put("all", buildAllComparisonInsights(rows));
        for (DisplayPeer peer : DISPLAY_PEERS) {
            if ("sk_ax".equals(peer.id())) {
                continue;
            }
            Map<String, Object> peerRow = rowById.get(peer.id());
            Map<String, Object> skAxRow = rowById.get("sk_ax");
            if (peerRow != null) {
                insights.put(peer.id(), buildPeerComparisonInsights(skAxRow, peerRow));
            }
        }
        return insights;
    }

    private List<Map<String, String>> buildAllComparisonInsights(List<Map<String, Object>> rows) {
        List<Map<String, Object>> peerRows = rows.stream()
                .filter(row -> !"sk_ax".equals(row.get("id")))
                .toList();
        String businessSummary = peerRows.stream()
                .map(row -> row.get("label") + "는 " + nullToDash(row.get("businessKeyword")))
                .reduce((left, right) -> left + ", " + right)
                .orElse("최근 사업 신호가 충분히 확보되지 않았습니다");
        String techSummary = peerRows.stream()
                .map(row -> row.get("label") + "는 " + nullToDash(row.get("technologyKeyword")))
                .reduce((left, right) -> left + ", " + right)
                .orElse("최근 기술 신호가 충분히 확보되지 않았습니다");
        String marginLeader = peerRows.stream()
                .filter(row -> nullableDouble(row.get("operatingMarginPct")) != null)
                .max((left, right) -> Double.compare(
                        nullableDouble(left.get("operatingMarginPct")),
                        nullableDouble(right.get("operatingMarginPct"))
                ))
                .map(row -> row.get("label") + " " + formatPercentText(nullableDouble(row.get("operatingMarginPct"))))
                .orElse("기업별 수익성 데이터가 제한적");

        return List.of(
                insight("포지셔닝", "전체 비교에서는 peer별 최근 사업·기술 신호가 어디에 집중되는지 보는 것이 중요합니다. " + businessSummary + " 축으로 갈라져 각 기업의 관심 영역과 비교 기준이 드러납니다."),
                insight("사업 신호", "최근 공개 원문 신호를 보면 " + businessSummary + " 관련 활동이 핵심 사업 차이로 나타납니다. SK AX는 이 차이를 기준으로 자사 AX 사업 방향과 겹치는 영역을 확인할 수 있습니다."),
                insight("기술 신호", "기술 축에서는 " + techSummary + " 흐름이 보여, SK AX가 보유한 구현 역량과 peer가 앞세우는 제품·플랫폼 축을 구분해 볼 수 있습니다."),
                insight("리스크", "Peer를 비교할 때는 최근 사업 신호와 공시 수치를 함께 봐야 합니다. 현재 수익성 기준으로는 " + marginLeader + "가 두드러지며, 세부 부문 공시 범위 차이는 비교 리스크로 남아 있습니다.")
        );
    }

    private List<Map<String, String>> buildPeerComparisonInsights(Map<String, Object> skAxRow, Map<String, Object> peerRow) {
        String peerLabel = String.valueOf(peerRow.get("label"));
        String skBusiness = skAxRow == null ? "-" : nullToDash(skAxRow.get("businessKeyword"));
        String skTech = skAxRow == null ? "-" : nullToDash(skAxRow.get("technologyKeyword"));
        String peerBusiness = nullToDash(peerRow.get("businessKeyword"));
        String peerTech = nullToDash(peerRow.get("technologyKeyword"));
        Double revenue = nullableDouble(peerRow.get("revenueKrwBn"));
        Double margin = nullableDouble(peerRow.get("operatingMarginPct"));
        Double marginDelta = nullableDouble(peerRow.get("operatingMarginQoqDeltaPctp"));

        return List.of(
                insight("포지셔닝", peerLabel + "는 사업 신호의 " + peerBusiness + ", 기술 신호의 " + peerTech + "를 앞세우는 비교 축으로 읽힙니다. SK AX의 " + skBusiness + "/" + skTech + " 흐름과 비교하면 확인해야 할 초점이 달라집니다."),
                insight("사업 신호", peerLabel + "는 최근 공개 원문에서 " + peerBusiness + " 관련 사업 활동이 가장 강하게 읽힙니다. SK AX는 이 축을 기준으로 자사 AX 사업 방향과 겹치거나 차이가 나는 영역을 볼 수 있습니다."),
                insight("기술 신호", peerLabel + "는 " + peerTech + "를 제품·플랫폼 또는 구현 역량의 중심으로 보여줍니다. SK AX는 " + skTech + "와의 차이를 기준으로 기술 관점의 비교 포인트를 정리할 수 있습니다."),
                insight("리스크", buildRiskInsight(peerLabel, revenue, margin, marginDelta))
        );
    }

    private Map<String, String> insight(String label, String body) {
        Map<String, String> item = new LinkedHashMap<>();
        item.put("label", label);
        item.put("body", body);
        return item;
    }

    private String buildRiskInsight(String peerLabel, Double revenue, Double margin, Double marginDelta) {
        String revenueText = revenue == null ? "매출 데이터가 제한적" : "매출 " + formatKrwBnText(revenue);
        String marginText = margin == null ? "영업이익률 데이터가 제한적" : "영업이익률 " + formatPercentText(margin);
        String deltaText = marginDelta == null ? "전분기 대비 수익성 변화는 확인이 제한적입니다" : "전분기 대비 영업이익률 변화는 " + formatPercentPointText(marginDelta) + "입니다";
        return peerLabel + "는 " + revenueText + ", " + marginText + " 기준으로 함께 봐야 합니다. " + deltaText + ". 따라서 최근 사업·기술 신호가 강하더라도 실적 범위와 수익성 변동은 별도 리스크로 남습니다.";
    }

    private String nullToDash(Object value) {
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            return stringValue;
        }
        return "-";
    }

    private String formatKrwBnText(Double value) {
        if (value == null) {
            return "-";
        }
        if (Math.abs(value) >= 10_000) {
            return String.format("%.2f조원", value / 10_000.0);
        }
        return String.format("%.0f억원", value);
    }

    private String formatPercentText(Double value) {
        if (value == null) {
            return "-";
        }
        return String.format("%.2f%%", value);
    }

    private String formatPercentPointText(Double value) {
        if (value == null) {
            return "-";
        }
        String sign = value > 0 ? "+" : "";
        return sign + String.format("%.2f%%p", value);
    }

    private List<Map<String, Object>> loadFinancialRows(String period) {
        if (period == null || period.isBlank()) {
            return List.of();
        }

        String sql = """
                WITH metric_rows AS (
                    SELECT
                        peer_id,
                        period,
                        CASE
                            WHEN COALESCE(NULLIF(metric_label, ''), metric_name) IN ('매출', '매출액', '총매출', 'revenue_total', 'revenue') THEN 'revenue_total'
                            WHEN COALESCE(NULLIF(metric_label, ''), metric_name) IN ('영업이익', 'operating_profit', 'operating_income') THEN 'operating_profit'
                            WHEN COALESCE(NULLIF(metric_label, ''), metric_name) IN ('영업이익률', 'operating_margin') THEN 'operating_margin'
                            WHEN COALESCE(NULLIF(metric_label, ''), metric_name) IN ('순이익', '당기순이익', 'net_income', 'net_profit') THEN 'net_income'
                            ELSE COALESCE(NULLIF(metric_label, ''), metric_name)
                        END AS metric_name_canonical,
                        value_krwbn,
                        value_numeric,
                        raw_article_id,
                        ROW_NUMBER() OVER (
                            PARTITION BY peer_id, period,
                                CASE
                                    WHEN COALESCE(NULLIF(metric_label, ''), metric_name) IN ('매출', '매출액', '총매출', 'revenue_total', 'revenue') THEN 'revenue_total'
                                    WHEN COALESCE(NULLIF(metric_label, ''), metric_name) IN ('영업이익', 'operating_profit', 'operating_income') THEN 'operating_profit'
                                    WHEN COALESCE(NULLIF(metric_label, ''), metric_name) IN ('영업이익률', 'operating_margin') THEN 'operating_margin'
                                    WHEN COALESCE(NULLIF(metric_label, ''), metric_name) IN ('순이익', '당기순이익', 'net_income', 'net_profit') THEN 'net_income'
                                    ELSE COALESCE(NULLIF(metric_label, ''), metric_name)
                                END
                            ORDER BY
                                confidence DESC NULLS LAST,
                                updated_at DESC NULLS LAST,
                                id DESC
                        ) AS row_rank
                    FROM raw_article_financial_metrics
                    WHERE metric_scope = 'company_total'
                      AND source_type = 'ir'
                      AND period IS NOT NULL
                      AND peer_id IN (?, ?, ?, ?, ?)
                      AND (
                          COALESCE(NULLIF(metric_label, ''), metric_name) NOT IN ('순이익', '당기순이익', 'net_income', 'net_profit')
                          OR business_area = 'company_total'
                      )
                ),
                latest_metric_rows AS (
                    SELECT *
                    FROM metric_rows
                    WHERE row_rank = 1
                ),
                pivoted AS (
                    SELECT
                        peer_id,
                        period,
                        MAX(CASE WHEN metric_name_canonical = 'revenue_total' THEN value_krwbn END) AS revenue_total_krwbn,
                        MAX(CASE WHEN metric_name_canonical = 'operating_profit' THEN value_krwbn END) AS operating_profit_krwbn,
                        MAX(CASE WHEN metric_name_canonical = 'net_income' THEN value_krwbn END) AS net_income_krwbn,
                        MAX(CASE WHEN metric_name_canonical = 'operating_margin'
                            THEN COALESCE(value_numeric::double precision, value_krwbn::double precision)
                        END) AS operating_margin_pct,
                        MAX(raw_article_id) FILTER (WHERE metric_name_canonical IN ('revenue_total', 'operating_profit', 'operating_margin', 'net_income')) AS raw_article_id
                    FROM latest_metric_rows
                    GROUP BY peer_id, period
                ),
                normalized_periods AS (
                    SELECT
                        peer_id,
                        period,
                        CASE
                            WHEN COALESCE(NULLIF(SUBSTRING(period FROM 'Q([1-4])$'), '')::int, 0) = 1 THEN
                                (COALESCE(NULLIF(SUBSTRING(period FROM '^([0-9]{4})'), '')::int, 0) - 1)::text || 'Q4'
                            ELSE
                                COALESCE(NULLIF(SUBSTRING(period FROM '^([0-9]{4})'), ''), '')
                                    || 'Q'
                                    || (COALESCE(NULLIF(SUBSTRING(period FROM 'Q([1-4])$'), '')::int, 0) - 1)::text
                        END AS previous_period,
                        revenue_total_krwbn,
                        operating_profit_krwbn,
                        net_income_krwbn,
                        CASE
                            WHEN operating_margin_pct IS NOT NULL THEN ROUND(operating_margin_pct::numeric, 2)::double precision
                            WHEN revenue_total_krwbn IS NOT NULL
                             AND revenue_total_krwbn <> 0
                             AND operating_profit_krwbn IS NOT NULL
                            THEN ROUND((operating_profit_krwbn / revenue_total_krwbn * 100)::numeric, 2)::double precision
                            ELSE NULL
                        END AS operating_margin_pct,
                        raw_article_id
                    FROM pivoted
                    WHERE period ~ '^[0-9]{4}Q[1-4]$'
                ),
                period_series AS (
                    SELECT
                        current_period.peer_id,
                        current_period.period,
                        current_period.revenue_total_krwbn,
                        current_period.operating_profit_krwbn,
                        current_period.net_income_krwbn,
                        current_period.operating_margin_pct,
                        current_period.raw_article_id,
                        previous_period.revenue_total_krwbn AS prev_revenue_total_krwbn,
                        previous_period.operating_profit_krwbn AS prev_operating_profit_krwbn,
                        previous_period.net_income_krwbn AS prev_net_income_krwbn,
                        previous_period.operating_margin_pct AS prev_operating_margin_pct
                    FROM normalized_periods current_period
                    LEFT JOIN normalized_periods previous_period
                      ON previous_period.peer_id = current_period.peer_id
                     AND previous_period.period = current_period.previous_period
                )
                SELECT
                    peer_id AS id,
                    revenue_total_krwbn,
                    CASE
                        WHEN prev_revenue_total_krwbn IS NOT NULL
                         AND prev_revenue_total_krwbn <> 0
                         AND revenue_total_krwbn IS NOT NULL
                        THEN ROUND(((revenue_total_krwbn - prev_revenue_total_krwbn) / ABS(prev_revenue_total_krwbn) * 100)::numeric, 2)
                        ELSE NULL
                    END AS revenue_qoq_pct,
                    operating_profit_krwbn,
                    CASE
                        WHEN prev_operating_profit_krwbn IS NOT NULL
                         AND prev_operating_profit_krwbn <> 0
                         AND operating_profit_krwbn IS NOT NULL
                        THEN ROUND(((operating_profit_krwbn - prev_operating_profit_krwbn) / ABS(prev_operating_profit_krwbn) * 100)::numeric, 2)
                        ELSE NULL
                    END AS operating_profit_qoq_pct,
                    net_income_krwbn,
                    CASE
                        WHEN prev_net_income_krwbn IS NOT NULL
                         AND prev_net_income_krwbn <> 0
                         AND net_income_krwbn IS NOT NULL
                        THEN ROUND(((net_income_krwbn - prev_net_income_krwbn) / ABS(prev_net_income_krwbn) * 100)::numeric, 2)
                        ELSE NULL
                    END AS net_income_qoq_pct,
                    operating_margin_pct,
                    CASE
                        WHEN prev_operating_margin_pct IS NOT NULL
                         AND operating_margin_pct IS NOT NULL
                        THEN ROUND((operating_margin_pct - prev_operating_margin_pct)::numeric, 2)
                        ELSE NULL
                    END AS operating_margin_qoq_delta_pctp,
                    NULL::text AS dart_rcept_no
                FROM period_series
                WHERE period = ?
                ORDER BY CASE peer_id
                    WHEN 'sk_ax' THEN 0
                    WHEN 'samsung_sds' THEN 1
                    WHEN 'lg_cns' THEN 2
                    WHEN 'hyundai_autoever' THEN 3
                    WHEN 'posco_dx' THEN 4
                    ELSE 99
                END
                """;

        return jdbcTemplate.query(
                sql,
                ps -> {
                    bindFinancialPeerIds(ps, 1);
                    ps.setString(FINANCIAL_PEER_IDS.size() + 1, period);
                },
                (rs, rowNum) -> mapFinancialRow(rs)
        );
    }

    private List<Map<String, Object>> loadPositioningPoints(String period) {
        if (period == null || period.isBlank()) {
            return List.of();
        }

        String sql = """
                WITH source_rows AS (
                    SELECT
                        rfm.peer_id,
                        rfm.period,
                        rfm.metric_name,
                        rfm.value_krwbn,
                        CASE
                            WHEN rfm.metric_name <> 'revenue_total_yoy' THEN rfm.value_numeric
                            WHEN rfm.value_numeric IS NULL THEN NULL
                            WHEN ABS(rfm.value_numeric) <= 200 THEN rfm.value_numeric
                            WHEN regexp_match(COALESCE(rfm.evidence_text, ''), '(?i)YoY\\s*([△▲+\\-]?)\\s*([0-9]+(?:\\.[0-9]+)?)%') IS NOT NULL THEN
                                (
                                    CASE
                                        WHEN (regexp_match(COALESCE(rfm.evidence_text, ''), '(?i)YoY\\s*([△▲+\\-]?)\\s*([0-9]+(?:\\.[0-9]+)?)%'))[1] IN ('△', '-') THEN -1
                                        ELSE 1
                                    END
                                ) * ((regexp_match(COALESCE(rfm.evidence_text, ''), '(?i)YoY\\s*([△▲+\\-]?)\\s*([0-9]+(?:\\.[0-9]+)?)%'))[2])::numeric
                            ELSE NULL
                        END AS normalized_value_numeric,
                        rfm.source_type,
                        rfm.confidence,
                        rfm.raw_article_id,
                        rfm.updated_at,
                        rfm.id
                    FROM raw_article_financial_metrics rfm
                    WHERE rfm.metric_scope = 'company_total'
                      AND rfm.business_area = 'company_total'
                      AND rfm.source_type = 'ir'
                      AND rfm.peer_id IN (?, ?, ?, ?, ?)
                      AND rfm.period ~ '^[0-9]{4}Q[1-4]$'
                      AND rfm.metric_name IN ('revenue_total', 'revenue_total_yoy')
                ),
                metric_rows AS (
                    SELECT
                        peer_id,
                        period,
                        metric_name,
                        value_krwbn,
                        normalized_value_numeric,
                        source_type,
                        confidence,
                        raw_article_id,
                        ROW_NUMBER() OVER (
                            PARTITION BY peer_id, period, metric_name
                            ORDER BY
                                CASE
                                    WHEN metric_name = 'revenue_total_yoy' AND normalized_value_numeric IS NULL THEN 1
                                    ELSE 0
                                END,
                                confidence DESC NULLS LAST,
                                updated_at DESC NULLS LAST,
                                id DESC
                        ) AS row_rank
                    FROM source_rows
                ),
                latest_metric_rows AS (
                    SELECT *
                    FROM metric_rows
                    WHERE row_rank = 1
                ),
                revenue_periods AS (
                    SELECT
                        peer_id,
                        period,
                        MAX(CASE WHEN metric_name = 'revenue_total' THEN value_krwbn END) AS revenue_krwbn,
                        MAX(CASE WHEN metric_name = 'revenue_total' THEN source_type END) AS revenue_source_type,
                        MAX(CASE WHEN metric_name = 'revenue_total' THEN confidence END) AS revenue_confidence,
                        MAX(CASE WHEN metric_name = 'revenue_total' THEN raw_article_id END) AS revenue_article_id,
                        MAX(CASE WHEN metric_name = 'revenue_total_yoy' THEN normalized_value_numeric END) AS explicit_yoy_pct,
                        MAX(CASE WHEN metric_name = 'revenue_total_yoy' THEN source_type END) AS explicit_yoy_source_type,
                        MAX(CASE WHEN metric_name = 'revenue_total_yoy' THEN confidence END) AS explicit_yoy_confidence,
                        MAX(CASE WHEN metric_name = 'revenue_total_yoy' THEN raw_article_id END) AS explicit_yoy_article_id
                    FROM latest_metric_rows
                    GROUP BY peer_id, period
                ),
                positioning_rows AS (
                    SELECT
                        current_rows.peer_id,
                        current_rows.period,
                        current_rows.revenue_krwbn,
                        COALESCE(
                            current_rows.explicit_yoy_pct,
                            CASE
                                WHEN previous_rows.revenue_krwbn IS NOT NULL
                                 AND previous_rows.revenue_krwbn <> 0
                                 AND current_rows.revenue_krwbn IS NOT NULL
                                THEN ((current_rows.revenue_krwbn - previous_rows.revenue_krwbn)
                                    / previous_rows.revenue_krwbn * 100)
                                ELSE NULL
                            END
                        ) AS revenue_yoy_pct,
                        current_rows.revenue_source_type,
                        CASE
                            WHEN current_rows.explicit_yoy_pct IS NOT NULL THEN current_rows.explicit_yoy_source_type
                            ELSE 'calculated'
                        END AS revenue_yoy_source_type,
                        current_rows.revenue_confidence,
                        COALESCE(
                            current_rows.explicit_yoy_confidence,
                            LEAST(current_rows.revenue_confidence, previous_rows.revenue_confidence)
                        ) AS revenue_yoy_confidence,
                        current_rows.revenue_article_id,
                        COALESCE(current_rows.explicit_yoy_article_id, previous_rows.revenue_article_id) AS revenue_yoy_article_id
                    FROM revenue_periods current_rows
                    LEFT JOIN revenue_periods previous_rows
                      ON previous_rows.peer_id = current_rows.peer_id
                     AND previous_rows.period = (
                         (COALESCE(NULLIF(SUBSTRING(current_rows.period FROM '^([0-9]{4})'), '')::int, 0) - 1)::text
                         || 'Q'
                         || COALESCE(NULLIF(SUBSTRING(current_rows.period FROM 'Q([1-4])$'), ''), '')
                     )
                )
                SELECT
                    peer_id AS id,
                    period,
                    ROUND(revenue_krwbn::numeric, 2) AS revenue_krwbn,
                    ROUND(revenue_yoy_pct::numeric, 2) AS revenue_yoy_pct,
                    revenue_source_type,
                    revenue_yoy_source_type,
                    ROUND(revenue_confidence::numeric, 3) AS revenue_confidence,
                    ROUND(revenue_yoy_confidence::numeric, 3) AS revenue_yoy_confidence,
                    revenue_article_id,
                    revenue_yoy_article_id
                FROM positioning_rows
                WHERE period = ?
                  AND revenue_krwbn IS NOT NULL
                  AND revenue_yoy_pct IS NOT NULL
                ORDER BY CASE peer_id
                    WHEN 'sk_ax' THEN 0
                    WHEN 'samsung_sds' THEN 1
                    WHEN 'lg_cns' THEN 2
                    WHEN 'hyundai_autoever' THEN 3
                    WHEN 'posco_dx' THEN 4
                    ELSE 99
                END
                """;

        Map<String, DisplayPeer> displayPeerById = new HashMap<>();
        for (DisplayPeer displayPeer : DISPLAY_PEERS) {
            displayPeerById.put(displayPeer.id(), displayPeer);
        }

        return jdbcTemplate.query(
                sql,
                ps -> {
                    bindFinancialPeerIds(ps, 1);
                    ps.setString(FINANCIAL_PEER_IDS.size() + 1, period);
                },
                (rs, rowNum) -> mapPositioningPoint(rs, displayPeerById)
        );
    }

    private List<Map<String, Object>> loadLatestPositioningPoints() {
        String sql = """
                WITH source_rows AS (
                    SELECT
                        rfm.peer_id,
                        rfm.period,
                        rfm.metric_name,
                        rfm.value_krwbn,
                        CASE
                            WHEN rfm.metric_name <> 'revenue_total_yoy' THEN rfm.value_numeric
                            WHEN rfm.value_numeric IS NULL THEN NULL
                            WHEN ABS(rfm.value_numeric) <= 200 THEN rfm.value_numeric
                            WHEN regexp_match(COALESCE(rfm.evidence_text, ''), '(?i)YoY\\s*([△▲+\\-]?)\\s*([0-9]+(?:\\.[0-9]+)?)%') IS NOT NULL THEN
                                (
                                    CASE
                                        WHEN (regexp_match(COALESCE(rfm.evidence_text, ''), '(?i)YoY\\s*([△▲+\\-]?)\\s*([0-9]+(?:\\.[0-9]+)?)%'))[1] IN ('△', '-') THEN -1
                                        ELSE 1
                                    END
                                ) * ((regexp_match(COALESCE(rfm.evidence_text, ''), '(?i)YoY\\s*([△▲+\\-]?)\\s*([0-9]+(?:\\.[0-9]+)?)%'))[2])::numeric
                            ELSE NULL
                        END AS normalized_value_numeric,
                        rfm.source_type,
                        rfm.confidence,
                        rfm.raw_article_id,
                        rfm.updated_at,
                        rfm.id
                    FROM raw_article_financial_metrics rfm
                    WHERE rfm.metric_scope = 'company_total'
                      AND rfm.business_area = 'company_total'
                      AND rfm.source_type = 'ir'
                      AND rfm.peer_id IN (?, ?, ?, ?, ?)
                      AND rfm.period ~ '^[0-9]{4}Q[1-4]$'
                      AND rfm.metric_name IN ('revenue_total', 'revenue_total_yoy')
                ),
                metric_rows AS (
                    SELECT
                        peer_id,
                        period,
                        metric_name,
                        value_krwbn,
                        normalized_value_numeric,
                        source_type,
                        confidence,
                        raw_article_id,
                        ROW_NUMBER() OVER (
                            PARTITION BY peer_id, period, metric_name
                            ORDER BY
                                CASE
                                    WHEN metric_name = 'revenue_total_yoy' AND normalized_value_numeric IS NULL THEN 1
                                    ELSE 0
                                END,
                                confidence DESC NULLS LAST,
                                updated_at DESC NULLS LAST,
                                id DESC
                        ) AS row_rank
                    FROM source_rows
                ),
                latest_metric_rows AS (
                    SELECT *
                    FROM metric_rows
                    WHERE row_rank = 1
                ),
                revenue_periods AS (
                    SELECT
                        peer_id,
                        period,
                        MAX(CASE WHEN metric_name = 'revenue_total' THEN value_krwbn END) AS revenue_krwbn,
                        MAX(CASE WHEN metric_name = 'revenue_total' THEN source_type END) AS revenue_source_type,
                        MAX(CASE WHEN metric_name = 'revenue_total' THEN confidence END) AS revenue_confidence,
                        MAX(CASE WHEN metric_name = 'revenue_total' THEN raw_article_id END) AS revenue_article_id,
                        MAX(CASE WHEN metric_name = 'revenue_total_yoy' THEN normalized_value_numeric END) AS explicit_yoy_pct,
                        MAX(CASE WHEN metric_name = 'revenue_total_yoy' THEN source_type END) AS explicit_yoy_source_type,
                        MAX(CASE WHEN metric_name = 'revenue_total_yoy' THEN confidence END) AS explicit_yoy_confidence,
                        MAX(CASE WHEN metric_name = 'revenue_total_yoy' THEN raw_article_id END) AS explicit_yoy_article_id
                    FROM latest_metric_rows
                    GROUP BY peer_id, period
                ),
                positioning_rows AS (
                    SELECT
                        current_rows.peer_id,
                        current_rows.period,
                        ROUND(current_rows.revenue_krwbn::numeric, 2) AS revenue_krwbn,
                        ROUND(COALESCE(
                            current_rows.explicit_yoy_pct,
                            CASE
                                WHEN previous_rows.revenue_krwbn IS NOT NULL
                                 AND previous_rows.revenue_krwbn <> 0
                                 AND current_rows.revenue_krwbn IS NOT NULL
                                THEN ((current_rows.revenue_krwbn - previous_rows.revenue_krwbn)
                                    / previous_rows.revenue_krwbn * 100)
                                ELSE NULL
                            END
                        )::numeric, 2) AS revenue_yoy_pct,
                        current_rows.revenue_source_type,
                        CASE
                            WHEN current_rows.explicit_yoy_pct IS NOT NULL THEN current_rows.explicit_yoy_source_type
                            ELSE 'calculated'
                        END AS revenue_yoy_source_type,
                        ROUND(current_rows.revenue_confidence::numeric, 3) AS revenue_confidence,
                        ROUND(COALESCE(
                            current_rows.explicit_yoy_confidence,
                            LEAST(current_rows.revenue_confidence, previous_rows.revenue_confidence)
                        )::numeric, 3) AS revenue_yoy_confidence,
                        current_rows.revenue_article_id,
                        COALESCE(current_rows.explicit_yoy_article_id, previous_rows.revenue_article_id) AS revenue_yoy_article_id
                    FROM revenue_periods current_rows
                    LEFT JOIN revenue_periods previous_rows
                      ON previous_rows.peer_id = current_rows.peer_id
                     AND previous_rows.period = (
                         (COALESCE(NULLIF(SUBSTRING(current_rows.period FROM '^([0-9]{4})'), '')::int, 0) - 1)::text
                         || 'Q'
                         || COALESCE(NULLIF(SUBSTRING(current_rows.period FROM 'Q([1-4])$'), ''), '')
                     )
                    WHERE current_rows.revenue_krwbn IS NOT NULL
                ),
                ranked_periods AS (
                    SELECT
                        *,
                        ROW_NUMBER() OVER (
                            PARTITION BY peer_id
                            ORDER BY
                                COALESCE(NULLIF(SUBSTRING(period FROM '^([0-9]{4})'), '')::int, 0) DESC,
                                COALESCE(NULLIF(SUBSTRING(period FROM 'Q([1-4])$'), '')::int, 0) DESC,
                                period DESC
                        ) AS period_rank
                    FROM positioning_rows
                    WHERE revenue_yoy_pct IS NOT NULL
                )
                SELECT
                    peer_id AS id,
                    period,
                    revenue_krwbn,
                    revenue_yoy_pct,
                    revenue_source_type,
                    revenue_yoy_source_type,
                    revenue_confidence,
                    revenue_yoy_confidence,
                    revenue_article_id,
                    revenue_yoy_article_id
                FROM ranked_periods
                WHERE period_rank = 1
                ORDER BY CASE peer_id
                    WHEN 'sk_ax' THEN 0
                    WHEN 'samsung_sds' THEN 1
                    WHEN 'lg_cns' THEN 2
                    WHEN 'hyundai_autoever' THEN 3
                    WHEN 'posco_dx' THEN 4
                    ELSE 99
                END
                """;

        Map<String, DisplayPeer> displayPeerById = new HashMap<>();
        for (DisplayPeer displayPeer : DISPLAY_PEERS) {
            displayPeerById.put(displayPeer.id(), displayPeer);
        }

        return jdbcTemplate.query(
                sql,
                ps -> bindFinancialPeerIds(ps, 1),
                (rs, rowNum) -> mapPositioningPoint(rs, displayPeerById)
        );
    }

    private Map<String, SupplementalRow> loadSupplementalRows() {
        try {
            String sql = """
                    WITH target_peers AS (
                        SELECT
                            pc.id,
                            pc.name,
                            pc.ax_revenue_share_pct,
                            (
                                SELECT item.keyword
                                FROM unnest(COALESCE(pc.core_keywords, ARRAY[]::text[]) || COALESCE(pc.keywords, ARRAY[]::text[]))
                                    WITH ORDINALITY AS item(keyword, sort_order)
                                WHERE NULLIF(trim(item.keyword), '') IS NOT NULL
                                  AND lower(regexp_replace(trim(item.keyword), '\\s+', ' ', 'g')) NOT IN (
                                      'sk ax',
                                      'samsung sds',
                                      'lg cns',
                                      'hyundai autoever',
                                      'posco dx',
                                      'sk_ax',
                                      'samsung_sds',
                                      'lg_cns',
                                      'hyundai_autoever',
                                      'posco_dx',
                                      '삼성sds',
                                      'lgcns',
                                      '현대오토에버',
                                      '포스코dx'
                                  )
                                ORDER BY item.sort_order
                                LIMIT 1
                            ) AS fallback_keyword
                        FROM peer_companies pc
                        WHERE pc.id IN ('sk_ax', 'samsung_sds', 'lg_cns', 'hyundai_autoever', 'posco_dx')
                    ),
                    fallback_keyword_axes(company_id, business_keyword, tech_keyword, business_context, tech_context) AS (
                        VALUES
                            ('sk_ax', '운영형 AX', 'AI 에이전트',
                                '고객 업무에 AI·DX 역량을 구축하고 운영하는 AX 사업 방향',
                                '업무 자동화와 의사결정 지원을 담당하는 AI 에이전트 기술'),
                            ('samsung_sds', 'AI 인프라', 'FabriX',
                                '클라우드·데이터센터 기반의 AI 인프라 사업',
                                '기업용 생성형 AI 플랫폼과 업무 자동화 기술'),
                            ('lg_cns', '클라우드 MSP', 'AI/DX',
                                '클라우드 전환·운영·관리형 서비스 사업',
                                '공공·금융·물류 영역에 적용되는 AI/DX 구현 기술'),
                            ('hyundai_autoever', '차량 SW', '커넥티드카',
                                '차량 소프트웨어와 제조 IT 운영 기반 사업',
                                '차량 연결 서비스, OTA, 모빌리티 플랫폼 기술'),
                            ('posco_dx', '산업 DX', '스마트팩토리',
                                '제조 현장 자동화와 산업 디지털 전환 사업',
                                '설비 지능화, 로봇, 스마트팩토리 구현 기술')
                    ),
                    axis_terms(company_id, axis_type, term, weight, label, context) AS (
                        VALUES
                            ('sk_ax', 'business', '운영형 AX', 5, '운영형 AX', '고객 업무를 AI 기반으로 전환하고 운영하는 AX 사업'),
                            ('sk_ax', 'business', '제조 AX', 4, '제조 AX', '제조 현장과 생산 업무를 AI로 전환하는 사업'),
                            ('sk_ax', 'business', '금융 AX', 4, '금융 AX', '금융 업무와 고객 서비스를 AI로 전환하는 사업'),
                            ('sk_ax', 'business', 'AI Transformation', 5, 'AI/Digital Transformation', 'AI와 DT 기반으로 고객 업무와 비즈니스 모델을 전환하는 사업'),
                            ('sk_ax', 'business', 'AI/Digital Transformation', 5, 'AI/Digital Transformation', 'AI와 DT 기반으로 고객 업무와 비즈니스 모델을 전환하는 사업'),
                            ('sk_ax', 'business', 'AI · DX', 5, 'AI/Digital Transformation', 'AI와 DX 기반의 신규 프로젝트와 전환 사업'),
                            ('sk_ax', 'business', 'AI·DX', 5, 'AI/Digital Transformation', 'AI와 DX 기반의 신규 프로젝트와 전환 사업'),
                            ('sk_ax', 'business', 'AI DX', 5, 'AI/Digital Transformation', 'AI와 DX 기반의 신규 프로젝트와 전환 사업'),
                            ('sk_ax', 'business', '클라우드', 2, '클라우드', 'AX 서비스를 운영하기 위한 클라우드 사업'),
                            ('sk_ax', 'business', 'Cloud', 2, '클라우드', 'AX 서비스를 운영하기 위한 클라우드 사업'),
                            ('sk_ax', 'business', '보안', 2, '보안', '기업 IT와 AX 환경을 보호하는 보안 사업'),
                            ('sk_ax', 'tech', 'AI 에이전트', 5, 'AI 에이전트', '업무 자동화와 의사결정을 수행하는 에이전트 기술'),
                            ('sk_ax', 'tech', '생성형 AI', 4, '생성형 AI', '기업 업무에 적용되는 생성형 AI 기술'),
                            ('sk_ax', 'tech', 'AI 솔루션', 5, 'AI 솔루션', '프로세스 자동화와 운영 효율 개선에 적용되는 AI 솔루션 기술'),
                            ('sk_ax', 'tech', 'AI Transformation', 4, 'AI 솔루션', '프로세스 자동화와 운영 효율 개선에 적용되는 AI 솔루션 기술'),
                            ('sk_ax', 'tech', 'Cloud 및 Data', 5, 'Cloud/Data 인프라', 'AI와 디지털 전환을 받치는 Cloud 및 Data 기반 인프라 기술'),
                            ('sk_ax', 'tech', 'Cloud', 3, 'Cloud/Data 인프라', 'AI와 디지털 전환을 받치는 Cloud 및 Data 기반 인프라 기술'),
                            ('sk_ax', 'tech', 'Data 기반', 4, 'Cloud/Data 인프라', 'AI와 디지털 전환을 받치는 Cloud 및 Data 기반 인프라 기술'),
                            ('sk_ax', 'tech', '디지털 인프라', 4, 'Cloud/Data 인프라', 'AI와 디지털 전환을 받치는 Cloud 및 Data 기반 인프라 기술'),
                            ('sk_ax', 'tech', 'LLM', 4, 'LLM', '기업 업무 자동화에 쓰이는 대형언어모델 기술'),
                            ('sk_ax', 'tech', 'AIOps', 3, 'AIOps', 'IT 운영을 자동화하는 AI 운영 기술'),
                            ('sk_ax', 'tech', 'MLOps', 3, 'MLOps', 'AI 모델 개발과 운영을 연결하는 기술'),
                            ('samsung_sds', 'business', '국가AI컴퓨팅센터', 6, '국가AI컴퓨팅센터', '국가 AI 컴퓨팅 인프라 구축 사업'),
                            ('samsung_sds', 'business', '국가 AI컴퓨팅센터', 6, '국가AI컴퓨팅센터', '국가 AI 컴퓨팅 인프라 구축 사업'),
                            ('samsung_sds', 'business', 'AI컴퓨팅센터', 5, '국가AI컴퓨팅센터', '국가 AI 컴퓨팅 인프라 구축 사업'),
                            ('samsung_sds', 'business', 'AI 컴퓨팅 센터', 5, '국가AI컴퓨팅센터', '국가 AI 컴퓨팅 인프라 구축 사업'),
                            ('samsung_sds', 'business', 'AI 인프라', 5, 'AI 인프라', '클라우드와 데이터센터 기반의 AI 인프라 사업'),
                            ('samsung_sds', 'business', '데이터센터', 4, '데이터센터', 'AI와 클라우드를 운영하는 데이터센터 사업'),
                            ('samsung_sds', 'business', '물류', 3, '물류', '디지털 물류 운영 사업'),
                            ('samsung_sds', 'business', '클라우드', 2, '클라우드', '기업 IT와 AI 서비스를 운영하는 클라우드 사업'),
                            ('samsung_sds', 'tech', 'FabriX', 6, 'FabriX', '기업용 생성형 AI 플랫폼'),
                            ('samsung_sds', 'tech', 'Brity', 5, 'Brity', '업무 자동화와 협업을 지원하는 솔루션'),
                            ('samsung_sds', 'tech', '생성형 AI', 4, '생성형 AI', '기업 업무에 적용되는 생성형 AI 기술'),
                            ('samsung_sds', 'tech', 'AI 에이전트', 4, 'AI 에이전트', '기업 업무를 자동화하는 에이전트 기술'),
                            ('samsung_sds', 'tech', '보안', 3, '보안', '클라우드와 기업 IT 환경을 보호하는 보안 기술'),
                            ('lg_cns', 'business', '클라우드 MSP', 6, '클라우드 MSP', '클라우드 전환·운영·관리형 서비스 사업'),
                            ('lg_cns', 'business', '국가 농업AX플랫폼', 6, '국가 농업AX플랫폼', '공공 농업 영역을 AI로 전환하는 플랫폼 사업'),
                            ('lg_cns', 'business', '농업AX플랫폼', 6, '국가 농업AX플랫폼', '공공 농업 영역을 AI로 전환하는 플랫폼 사업'),
                            ('lg_cns', 'business', '스마트물류', 5, '스마트물류', '물류센터와 배송 운영을 디지털화하는 사업'),
                            ('lg_cns', 'business', '공공 DX', 4, '공공 DX', '공공기관 업무와 시스템을 디지털 전환하는 사업'),
                            ('lg_cns', 'business', '금융 DX', 4, '금융 DX', '금융권 업무와 시스템을 디지털 전환하는 사업'),
                            ('lg_cns', 'business', '데이터센터', 3, '데이터센터', '클라우드 서비스를 운영하는 데이터센터 사업'),
                            ('lg_cns', 'tech', 'AI/DX', 5, 'AI/DX', 'AI와 디지털 전환 구현 기술'),
                            ('lg_cns', 'tech', '생성형 AI', 4, '생성형 AI', '공공·금융·물류 업무에 적용되는 생성형 AI 기술'),
                            ('lg_cns', 'tech', '클라우드', 3, '클라우드', '클라우드 전환과 운영 기술'),
                            ('lg_cns', 'tech', '데이터센터', 3, '데이터센터', '클라우드와 AI 서비스를 운영하는 인프라 기술'),
                            ('lg_cns', 'tech', '보안', 3, '보안', '공공·금융 시스템을 보호하는 보안 기술'),
                            ('lg_cns', 'tech', '휴머노이드', 4, '휴머노이드', '로봇과 AI를 결합한 자동화 기술'),
                            ('hyundai_autoever', 'business', '차량 SW', 6, '차량 SW', '차량 기능과 서비스를 소프트웨어로 구현하는 사업'),
                            ('hyundai_autoever', 'business', '커넥티드카', 5, '커넥티드카', '차량 연결 서비스와 모빌리티 플랫폼 사업'),
                            ('hyundai_autoever', 'business', '스마트팩토리', 4, '스마트팩토리', '제조 현장을 디지털화하는 사업'),
                            ('hyundai_autoever', 'business', '제조 DX', 4, '제조 DX', '제조 업무와 생산 시스템을 디지털 전환하는 사업'),
                            ('hyundai_autoever', 'business', '제조 시스템 인프라', 4, '제조 시스템 인프라', '제조 운영을 받치는 IT 인프라 사업'),
                            ('hyundai_autoever', 'tech', 'OTA', 5, 'OTA', '차량 소프트웨어를 원격으로 업데이트하는 기술'),
                            ('hyundai_autoever', 'tech', '로보틱스 소프트웨어', 6, '로보틱스 소프트웨어', '로봇 인지·판단·제어를 구현하는 소프트웨어 기술'),
                            ('hyundai_autoever', 'tech', '내비게이션', 4, '내비게이션', '차량 주행과 위치 서비스를 지원하는 기술'),
                            ('hyundai_autoever', 'tech', '자율주행', 4, '자율주행', '차량 주행 판단과 제어를 자동화하는 기술'),
                            ('hyundai_autoever', 'tech', '지능형 제조 시스템', 4, '지능형 제조 시스템', '제조 현장을 데이터와 AI로 운영하는 기술'),
                            ('posco_dx', 'business', '산업 DX', 6, '산업 DX', '제조·제철 현장을 디지털 전환하는 사업'),
                            ('posco_dx', 'business', '스마트팩토리', 5, '스마트팩토리', '제조 현장 자동화와 지능화 사업'),
                            ('posco_dx', 'business', '이차전지', 4, '이차전지', '이차전지 소재·공정 영역의 디지털 전환 사업'),
                            ('posco_dx', 'business', '제조 자동화', 5, '제조 자동화', '제조 현장 설비와 공정을 자동화하는 사업'),
                            ('posco_dx', 'business', '설비 지능화', 5, '설비 지능화', '제조 설비를 데이터와 AI로 지능화하는 사업'),
                            ('posco_dx', 'tech', '휴머노이드 로봇', 6, '휴머노이드 로봇', '제조 현장에 투입되는 휴머노이드 로봇 기술'),
                            ('posco_dx', 'tech', '로봇', 4, '로봇', '제조 현장 자동화를 위한 로봇 기술'),
                            ('posco_dx', 'tech', 'AI', 3, 'AI', '제조 현장 판단과 자동화를 지원하는 AI 기술'),
                            ('posco_dx', 'tech', 'LLM', 4, 'LLM', '현장 데이터와 업무 자동화를 위한 대형언어모델 기술'),
                            ('posco_dx', 'tech', '디지털 트윈', 4, '디지털 트윈', '제조 설비와 공정을 가상화해 운영하는 기술')
                    ),
                    business_terms(company_id, term, weight, label) AS (
                        VALUES
                            ('sk_ax', '운영형 AX', 3, '운영형 AX'),
                            ('sk_ax', 'AI 에이전트', 3, 'AI 에이전트'),
                            ('sk_ax', '클라우드', 1, '클라우드'),
                            ('sk_ax', '보안', 1, '보안'),
                            ('sk_ax', '제조 AX', 2, '제조 AX'),
                            ('sk_ax', '금융 AX', 2, '금융 AX'),
                            ('samsung_sds', 'FabriX', 3, 'FabriX'),
                            ('samsung_sds', 'Brity', 3, 'Brity'),
                            ('samsung_sds', 'AI 인프라', 3, 'AI 인프라'),
                            ('samsung_sds', '국가AI컴퓨팅센터', 5, '국가AI컴퓨팅센터'),
                            ('samsung_sds', '국가 AI컴퓨팅센터', 5, '국가AI컴퓨팅센터'),
                            ('samsung_sds', '국가 AI컴퓨팅 센터', 5, '국가AI컴퓨팅센터'),
                            ('samsung_sds', 'AI컴퓨팅센터', 4, '국가AI컴퓨팅센터'),
                            ('samsung_sds', 'AI 컴퓨팅센터', 4, '국가AI컴퓨팅센터'),
                            ('samsung_sds', 'AI컴퓨팅 센터', 4, '국가AI컴퓨팅센터'),
                            ('samsung_sds', 'AI 컴퓨팅 센터', 4, '국가AI컴퓨팅센터'),
                            ('samsung_sds', '데이터센터', 2, '데이터센터'),
                            ('samsung_sds', '클라우드', 2, '클라우드'),
                            ('samsung_sds', '물류', 2, '물류'),
                            ('samsung_sds', '보안', 1, '보안'),
                            ('lg_cns', '클라우드 MSP', 3, '클라우드 MSP'),
                            ('lg_cns', 'IT 인프라', 2, '클라우드/IT 인프라'),
                            ('lg_cns', '공공 DX', 2, '공공 DX'),
                            ('lg_cns', '금융 DX', 2, '금융 DX'),
                            ('lg_cns', '스마트물류', 3, '스마트물류'),
                            ('lg_cns', 'AI/DX', 2, 'AI/DX'),
                            ('lg_cns', '데이터센터', 2, '데이터센터'),
                            ('lg_cns', '국가 농업AX플랫폼', 5, '국가 농업AX플랫폼'),
                            ('lg_cns', '농업AX플랫폼', 5, '국가 농업AX플랫폼'),
                            ('lg_cns', '농업 AX플랫폼', 5, '국가 농업AX플랫폼'),
                            ('hyundai_autoever', '차량 SW', 3, '차량 SW'),
                            ('hyundai_autoever', '커넥티드카', 3, '커넥티드카'),
                            ('hyundai_autoever', 'OTA', 3, 'OTA'),
                            ('hyundai_autoever', '내비게이션', 2, '내비게이션'),
                            ('hyundai_autoever', '스마트팩토리', 3, '스마트팩토리'),
                            ('hyundai_autoever', '제조 DX', 2, '제조 DX'),
                            ('hyundai_autoever', '로보틱스 소프트웨어', 5, '로보틱스 소프트웨어'),
                            ('hyundai_autoever', '로봇 인지', 4, '로보틱스 소프트웨어'),
                            ('hyundai_autoever', '로봇 판단', 4, '로보틱스 소프트웨어'),
                            ('hyundai_autoever', '제조 시스템 인프라', 3, '차량/제조 IT 인프라'),
                            ('hyundai_autoever', '지능형 제조 시스템', 3, '차량/제조 IT 인프라'),
                            ('posco_dx', '산업 DX', 3, '산업 DX'),
                            ('posco_dx', '스마트팩토리', 3, '스마트팩토리'),
                            ('posco_dx', '이차전지', 3, '이차전지'),
                            ('posco_dx', '로봇', 2, '로봇'),
                            ('posco_dx', '휴머노이드 로봇', 5, '휴머노이드 로봇'),
                            ('posco_dx', '제철소 휴머노이드', 5, '휴머노이드 로봇'),
                            ('posco_dx', '제조 자동화', 3, '제조 자동화'),
                            ('posco_dx', '설비 지능화', 3, '설비 지능화')
                    ),
                    category_terms(company_id, keyword_key, weight, label, relation_label) AS (
                        VALUES
                            ('samsung_sds', 'infra', 3, 'AI 인프라', '클라우드·데이터센터·AI 인프라 사업'),
                            ('samsung_sds', 'security', 2, '보안', '클라우드·기업 IT 보안 사업'),
                            ('samsung_sds', 'deal', 2, '수주/계약', 'IT서비스·클라우드 사업 수주/계약 활동'),
                            ('samsung_sds', 'ma', 2, 'M&A', '사업 포트폴리오 확장 활동'),
                            ('samsung_sds', 'partnership', 1, '협력/제휴', '클라우드·AI 생태계 협력 활동'),
                            ('samsung_sds', 'expansion', 1, '사업 확장', '클라우드·AI 사업 확장 활동'),
                            ('lg_cns', 'infra', 3, '클라우드 인프라', '클라우드 MSP·데이터센터 운영 사업'),
                            ('lg_cns', 'security', 2, '보안', '클라우드·공공/금융 시스템 보안 사업'),
                            ('lg_cns', 'deal', 2, '수주/계약', '공공·금융·클라우드 DX 수주/계약 활동'),
                            ('lg_cns', 'ma', 2, 'M&A', 'DX 사업 포트폴리오 확장 활동'),
                            ('lg_cns', 'partnership', 1, '협력/제휴', '클라우드·AI/DX 생태계 협력 활동'),
                            ('lg_cns', 'expansion', 1, '사업 확장', '클라우드·DX 사업 확장 활동'),
                            ('lg_cns', 'regulation', 1, '규제', '공공·금융 DX 사업의 규제 대응 문맥'),
                            ('hyundai_autoever', 'infra', 2, '차량/제조 IT 인프라', '차량 SW·제조 IT 운영 기반 사업'),
                            ('hyundai_autoever', 'partnership', 1, '협력/제휴', '차량 SW·모빌리티 플랫폼 협력 활동'),
                            ('posco_dx', 'partnership', 1, '협력/제휴', '산업 DX·스마트팩토리 협력 활동')
                    ),
                    action_terms(term, weight, label, evidence_priority) AS (
                        VALUES
                            ('수주', 4, '수주', 95),
                            ('계약', 4, '계약', 92),
                            ('담당', 2, '담당', 90),
                            ('체결', 3, '체결', 88),
                            ('선정', 3, '선정', 86),
                            ('착수', 3, '착수', 84),
                            ('실증', 3, '실증', 82),
                            ('출시', 4, '출시', 80),
                            ('투자', 4, '투자', 78),
                            ('공급', 4, '공급', 76),
                            ('납품', 4, '납품', 74),
                            ('상용화', 4, '상용화', 72),
                            ('적용', 3, '적용', 70),
                            ('도입', 3, '도입', 68),
                            ('구축', 4, '구축', 66),
                            ('개발', 3, '개발', 64),
                            ('고도화', 3, '고도화', 62),
                            ('운영', 3, '운영', 60),
                            ('확대', 3, '확대', 58),
                            ('협력', 2, '협력', 56),
                            ('제휴', 2, '제휴', 54),
                            ('제공', 3, '제공', 52)
                    ),
                    generic_keywords(keyword_key) AS (
                        VALUES
                            ('ai'),
                            ('dx'),
                            ('ax'),
                            ('인프라'),
                            ('정부'),
                            ('협약'),
                            ('수주'),
                            ('계약'),
                            ('서비스'),
                            ('플랫폼'),
                            ('사업'),
                            ('기술'),
                            ('infra'),
                            ('security'),
                            ('deal'),
                            ('partnership'),
                            ('expansion'),
                            ('ma')
                    ),
                    blocked_keywords(keyword_key) AS (
                        VALUES
                            ('contract'),
                            ('tech_release'),
                            ('other'),
                            ('market_trend'),
                            ('market'),
                            ('financial'),
                            ('sk ax'),
                            ('samsung sds'),
                            ('lg cns'),
                            ('hyundai autoever'),
                            ('posco dx'),
                            ('sk_ax'),
                            ('samsung_sds'),
                            ('lg_cns'),
                            ('hyundai_autoever'),
                            ('posco_dx'),
                            ('personnel'),
                            ('company'),
                            ('tech'),
                            ('regulation'),
                            ('ax'),
                            ('dx'),
                            ('ai')
                    ),
                    keyword_candidates AS (
                        SELECT
                            rabs.id AS card_id,
                            rabs.peer_id AS company_id,
                            trim(keyword_value) AS keyword,
                            lower(regexp_replace(trim(keyword_value), '\\s+', ' ', 'g')) AS keyword_key,
                            regexp_replace(lower(trim(keyword_value)), '\\s+', '', 'g') AS keyword_compact,
                            lower(
                                COALESCE(ra.title, '')
                                || ' '
                                || COALESCE(rabs.business_area, '')
                                || ' '
                                || COALESCE(rabs.signal_type, '')
                                || ' '
                                || COALESCE(rabs.summary, '')
                                || ' '
                                || COALESCE(rabs.evidence_text, '')
                            ) AS text_norm,
                            regexp_replace(lower(
                                COALESCE(ra.title, '')
                                || ' '
                                || COALESCE(rabs.business_area, '')
                                || ' '
                                || COALESCE(rabs.signal_type, '')
                                || ' '
                                || COALESCE(rabs.summary, '')
                                || ' '
                                || COALESCE(rabs.evidence_text, '')
                            ), '\\s+', '', 'g') AS text_compact,
                            regexp_replace(lower(
                                COALESCE(ra.title, '')
                                || ' '
                                || COALESCE(rabs.summary, '')
                                || ' '
                                || COALESCE(rabs.evidence_text, '')
                            ), '\\s+', '', 'g') AS evidence_compact,
                            NULLIF(trim(
                                COALESCE(ra.title, '')
                                || CASE
                                    WHEN NULLIF(trim(COALESCE(rabs.summary, '')), '') IS NOT NULL
                                    THEN ' - ' || COALESCE(rabs.summary, '')
                                    WHEN NULLIF(trim(COALESCE(rabs.evidence_text, '')), '') IS NOT NULL
                                    THEN ' - ' || COALESCE(rabs.evidence_text, '')
                                    ELSE ''
                                END
                            ), '') AS evidence_text,
                            NULLIF(trim(COALESCE(rabs.evidence_text, rabs.summary, '')), '') AS evidence_quote,
                            NULLIF(trim(COALESCE(ra.content, '')), '') AS article_content,
                            COALESCE(ra.published_at, rabs.created_at) AS evidence_at,
                            NULLIF(ra.url, '') AS evidence_url
                        FROM raw_article_business_signals rabs
                        JOIN raw_articles ra
                          ON ra.id = rabs.raw_article_id
                        CROSS JOIN LATERAL (
                            SELECT rabs.business_area AS keyword_value
                            UNION ALL
                            SELECT rabs.signal_type AS keyword_value
                            WHERE NULLIF(trim(COALESCE(rabs.signal_type, '')), '') IS NOT NULL
                        ) keywords
                        WHERE rabs.peer_id IN ('sk_ax', 'samsung_sds', 'lg_cns', 'hyundai_autoever', 'posco_dx')
                          AND COALESCE(ra.published_at, rabs.created_at) >= NOW() - INTERVAL '180 days'
                          AND NULLIF(trim(keyword_value), '') IS NOT NULL
                          AND lower(regexp_replace(trim(keyword_value), '\\s+', ' ', 'g')) NOT IN (
                              SELECT keyword_key FROM blocked_keywords
                          )
                    ),
                    keyword_rows AS (
                        SELECT DISTINCT
                            card_id,
                            company_id,
                            keyword_key,
                            keyword,
                            keyword_compact,
                            text_norm,
                            text_compact,
                            evidence_compact,
                            evidence_text,
                            evidence_quote,
                            article_content,
                            evidence_at,
                            evidence_url
                        FROM keyword_candidates
                        WHERE length(keyword) >= 2
                    ),
                    evidence_rows AS (
                        SELECT
	                            kr.*,
	                            COALESCE(MAX(bt.weight), 0) AS business_match_score,
	                            COALESCE(MAX(text_bt.weight), 0) AS text_business_match_score,
	                            COALESCE(MAX(ct.weight), 0) AS category_match_score,
	                            COALESCE(MAX(at.weight), 0) AS action_relation_score,
                            string_agg(DISTINCT bt.label, ', ' ORDER BY bt.label) FILTER (WHERE bt.label IS NOT NULL) AS business_labels,
                            string_agg(DISTINCT text_bt.label, ', ' ORDER BY text_bt.label) FILTER (WHERE text_bt.label IS NOT NULL) AS text_business_labels,
                            string_agg(DISTINCT ct.label, ', ' ORDER BY ct.label) FILTER (WHERE ct.label IS NOT NULL) AS category_labels,
                            string_agg(DISTINCT ct.relation_label, ', ' ORDER BY ct.relation_label) FILTER (WHERE ct.relation_label IS NOT NULL) AS category_relation_labels,
                            (array_agg(text_bt.label ORDER BY text_bt.weight DESC, length(text_bt.term) DESC) FILTER (WHERE text_bt.label IS NOT NULL))[1] AS primary_text_business_label,
                            (array_agg(bt.label ORDER BY bt.weight DESC, length(bt.term) DESC) FILTER (WHERE bt.label IS NOT NULL))[1] AS primary_business_label,
                            (array_agg(ct.label ORDER BY ct.weight DESC, length(ct.label) DESC) FILTER (WHERE ct.label IS NOT NULL))[1] AS primary_category_label,
                            string_agg(DISTINCT at.label, ', ' ORDER BY at.label) FILTER (WHERE at.label IS NOT NULL) AS action_labels,
                            (array_agg(at.label ORDER BY at.evidence_priority DESC, length(at.term) DESC) FILTER (
                                WHERE at.label IS NOT NULL
                                  AND kr.evidence_compact LIKE '%' || regexp_replace(lower(at.term), '\\s+', '', 'g') || '%'
                            ))[1] AS primary_action_label,
                            CASE WHEN g.keyword_key IS NOT NULL THEN 1 ELSE 0 END AS is_generic
                        FROM keyword_rows kr
                        LEFT JOIN business_terms bt
                          ON bt.company_id = kr.company_id
                         AND (
                            kr.keyword_compact = regexp_replace(lower(bt.term), '\\s+', '', 'g')
	                            OR kr.keyword_compact LIKE '%' || regexp_replace(lower(bt.term), '\\s+', '', 'g') || '%'
	                            OR regexp_replace(lower(bt.term), '\\s+', '', 'g') LIKE '%' || kr.keyword_compact || '%'
	                         )
	                        LEFT JOIN business_terms text_bt
	                          ON text_bt.company_id = kr.company_id
	                         AND kr.text_compact LIKE '%' || regexp_replace(lower(text_bt.term), '\\s+', '', 'g') || '%'
	                         AND (
	                            kr.keyword_key <> 'infra'
	                            OR regexp_replace(lower(text_bt.term), '\\s+', '', 'g') IN (
	                                'ai인프라',
	                                'ai컴퓨팅센터',
	                                '데이터센터',
	                                '클라우드',
	                                '클라우드msp',
	                                'it인프라',
	                                '제조시스템인프라',
	                                '지능형제조시스템'
	                            )
	                         )
	                        LEFT JOIN category_terms ct
                          ON ct.company_id = kr.company_id
                         AND ct.keyword_key = kr.keyword_key
                        LEFT JOIN action_terms at
                          ON kr.text_compact LIKE '%' || regexp_replace(lower(at.term), '\\s+', '', 'g') || '%'
                        LEFT JOIN generic_keywords g
                          ON g.keyword_key = kr.keyword_key
                        GROUP BY
                            kr.card_id,
                            kr.company_id,
                            kr.keyword_key,
                            kr.keyword,
                            kr.keyword_compact,
                            kr.text_norm,
                            kr.text_compact,
                            kr.evidence_compact,
                            kr.evidence_text,
                            kr.evidence_quote,
                            kr.article_content,
                            kr.evidence_at,
                            kr.evidence_url,
                            g.keyword_key
                    ),
                    axis_evidence_rows AS (
                        SELECT
                            kc.company_id,
                            atx.axis_type,
                            atx.label,
                            atx.context,
                            kc.card_id,
                            kc.evidence_text,
                            NULLIF(trim(regexp_replace(
                                CASE
                                    WHEN position(lower(atx.term) in lower(COALESCE(kc.article_content, ''))) > 0
                                    THEN substring(
                                        kc.article_content
                                        FROM GREATEST(position(lower(atx.term) in lower(COALESCE(kc.article_content, ''))) - 90, 1)
                                        FOR 260
                                    )
                                    WHEN position(lower(atx.label) in lower(COALESCE(kc.article_content, ''))) > 0
                                    THEN substring(
                                        kc.article_content
                                        FROM GREATEST(position(lower(atx.label) in lower(COALESCE(kc.article_content, ''))) - 90, 1)
                                        FOR 260
                                    )
                                    ELSE COALESCE(kc.evidence_quote, kc.evidence_text)
                                END,
                                '\\s+',
                                ' ',
                                'g'
                            )), '') AS evidence_quote,
                            kc.evidence_url,
                            kc.evidence_at,
                            COALESCE(MAX(atx.weight), 0) AS term_match_score,
                            COALESCE(MAX(act.weight), 0) AS action_relation_score,
                            (array_agg(act.label ORDER BY act.evidence_priority DESC, length(act.term) DESC) FILTER (
                                WHERE act.label IS NOT NULL
                                  AND kc.evidence_compact LIKE '%' || regexp_replace(lower(act.term), '\\s+', '', 'g') || '%'
                            ))[1] AS primary_action_label
                        FROM keyword_candidates kc
                        JOIN axis_terms atx
                          ON atx.company_id = kc.company_id
                         AND (
                            kc.text_compact LIKE '%' || regexp_replace(lower(atx.term), '\\s+', '', 'g') || '%'
                            OR kc.keyword_compact = regexp_replace(lower(atx.term), '\\s+', '', 'g')
                            OR kc.keyword_compact LIKE '%' || regexp_replace(lower(atx.term), '\\s+', '', 'g') || '%'
                         )
                        LEFT JOIN action_terms act
                          ON kc.text_compact LIKE '%' || regexp_replace(lower(act.term), '\\s+', '', 'g') || '%'
                        WHERE kc.evidence_text IS NOT NULL
                        GROUP BY
                            kc.company_id,
                            atx.axis_type,
                            atx.label,
                            atx.context,
                            atx.term,
                            kc.card_id,
                            kc.evidence_text,
                            kc.evidence_quote,
                            kc.article_content,
                            kc.evidence_url,
                            kc.evidence_at
                    ),
                    axis_keyword_agg AS (
                        SELECT
                            company_id,
                            axis_type,
                            label,
                            context,
                            COUNT(DISTINCT card_id) AS evidence_count,
                            COUNT(DISTINCT card_id) FILTER (
                                WHERE evidence_at >= NOW() - INTERVAL '30 days'
                            ) AS recent_30_count,
                            COUNT(DISTINCT card_id) FILTER (
                                WHERE evidence_at >= NOW() - INTERVAL '90 days'
                            ) AS recent_90_count,
                            MAX(evidence_at) AS latest_article_at,
                            MAX(term_match_score) AS term_match_score,
                            MAX(action_relation_score) AS action_relation_score,
                            (array_agg(primary_action_label ORDER BY action_relation_score DESC, evidence_at DESC NULLS LAST) FILTER (
                                WHERE primary_action_label IS NOT NULL
                            ))[1] AS primary_action_label,
                            (array_agg(evidence_text ORDER BY term_match_score DESC, action_relation_score DESC, evidence_at DESC NULLS LAST) FILTER (
                                WHERE evidence_text IS NOT NULL
                            ))[1] AS selected_evidence_text,
                            (array_agg(evidence_url ORDER BY term_match_score DESC, action_relation_score DESC, evidence_at DESC NULLS LAST) FILTER (
                                WHERE evidence_url IS NOT NULL
                            ))[1] AS selected_evidence_url,
                            (array_agg(evidence_quote ORDER BY term_match_score DESC, action_relation_score DESC, evidence_at DESC NULLS LAST) FILTER (
                                WHERE evidence_quote IS NOT NULL
                            ))[1] AS selected_evidence_quote
                        FROM axis_evidence_rows
                        GROUP BY company_id, axis_type, label, context
                    ),
                    ranked_axes AS (
                        SELECT
                            *,
                            ROUND((
                                term_match_score * 2.0
                                + action_relation_score * 0.8
                                + CASE
                                    WHEN latest_article_at >= NOW() - INTERVAL '30 days' THEN 1.0
                                    WHEN latest_article_at >= NOW() - INTERVAL '90 days' THEN 0.7
                                    ELSE 0.4
                                  END
                                + LEAST(1.5, evidence_count::double precision / 8.0)
                                + LEAST(
                                    2,
                                    recent_30_count::double precision * 0.6
                                        + GREATEST(0, recent_90_count - recent_30_count)::double precision * 0.25
                                  )
                            )::numeric, 3) AS final_score,
                            ROW_NUMBER() OVER (
                                PARTITION BY company_id, axis_type
                                ORDER BY
                                    (
                                        term_match_score * 2.0
                                        + action_relation_score * 0.8
                                        + CASE
                                            WHEN latest_article_at >= NOW() - INTERVAL '30 days' THEN 1.0
                                            WHEN latest_article_at >= NOW() - INTERVAL '90 days' THEN 0.7
                                            ELSE 0.4
                                          END
                                        + LEAST(1.5, evidence_count::double precision / 8.0)
                                        + LEAST(
                                            2,
                                            recent_30_count::double precision * 0.6
                                                + GREATEST(0, recent_90_count - recent_30_count)::double precision * 0.25
                                          )
                                    ) DESC,
                                    term_match_score DESC,
                                    action_relation_score DESC,
                                    latest_article_at DESC NULLS LAST,
                                    length(label) DESC,
                                    label ASC
                            ) AS axis_rank
                        FROM axis_keyword_agg
                        WHERE recent_90_count > 0
                          AND term_match_score >= 2
                    ),
                    selected_axes AS (
                        SELECT
                            tp.id AS company_id,
                            MAX(ra.label) FILTER (WHERE ra.axis_type = 'business' AND ra.axis_rank = 1) AS business_keyword,
                            MAX(ra.label) FILTER (WHERE ra.axis_type = 'tech' AND ra.axis_rank = 1) AS tech_keyword,
                            MAX(ra.context) FILTER (WHERE ra.axis_type = 'business' AND ra.axis_rank = 1) AS business_context,
                            MAX(ra.context) FILTER (WHERE ra.axis_type = 'tech' AND ra.axis_rank = 1) AS tech_context,
                            MAX(ra.selected_evidence_text) FILTER (WHERE ra.axis_type = 'business' AND ra.axis_rank = 1) AS business_evidence_text,
                            MAX(ra.selected_evidence_text) FILTER (WHERE ra.axis_type = 'tech' AND ra.axis_rank = 1) AS tech_evidence_text,
                            MAX(ra.selected_evidence_url) FILTER (WHERE ra.axis_type = 'business' AND ra.axis_rank = 1) AS business_evidence_url,
                            MAX(ra.selected_evidence_url) FILTER (WHERE ra.axis_type = 'tech' AND ra.axis_rank = 1) AS tech_evidence_url,
                            MAX(ra.selected_evidence_quote) FILTER (WHERE ra.axis_type = 'business' AND ra.axis_rank = 1) AS business_evidence_quote,
                            MAX(ra.selected_evidence_quote) FILTER (WHERE ra.axis_type = 'tech' AND ra.axis_rank = 1) AS tech_evidence_quote,
                            MAX(ra.primary_action_label) FILTER (WHERE ra.axis_type = 'business' AND ra.axis_rank = 1) AS business_action_label,
                            MAX(ra.primary_action_label) FILTER (WHERE ra.axis_type = 'tech' AND ra.axis_rank = 1) AS tech_action_label,
                            COALESCE(MAX(ra.final_score) FILTER (WHERE ra.axis_type = 'business' AND ra.axis_rank = 1), 0)
                                + COALESCE(MAX(ra.final_score) FILTER (WHERE ra.axis_type = 'tech' AND ra.axis_rank = 1), 0) AS final_score
                        FROM target_peers tp
                        LEFT JOIN fallback_keyword_axes fka
                          ON fka.company_id = tp.id
                        LEFT JOIN ranked_axes ra
                          ON ra.company_id = tp.id
                         AND ra.axis_rank = 1
                        GROUP BY
                            tp.id,
                            fka.business_keyword,
                            fka.tech_keyword,
                            fka.business_context,
                            fka.tech_context
                    ),
                    keyword_agg AS (
                        SELECT
                            company_id,
                            keyword_key,
	                            MIN(keyword) AS keyword,
	                            COUNT(DISTINCT card_id) AS evidence_count,
	                            COUNT(DISTINCT card_id) FILTER (
	                                WHERE evidence_at >= NOW() - INTERVAL '30 days'
	                            ) AS recent_30_count,
	                            COUNT(DISTINCT card_id) FILTER (
	                                WHERE evidence_at >= NOW() - INTERVAL '90 days'
	                            ) AS recent_90_count,
	                            COUNT(DISTINCT card_id) FILTER (
	                                WHERE action_relation_score > 0
	                                   OR business_match_score > 0
	                                   OR text_business_match_score > 0
	                            ) AS direct_evidence_count,
	                            COUNT(DISTINCT card_id) FILTER (
	                                WHERE evidence_at >= NOW() - INTERVAL '90 days'
	                                  AND (
	                                      action_relation_score > 0
	                                      OR business_match_score > 0
	                                      OR text_business_match_score > 0
	                                  )
	                            ) AS recent_direct_evidence_count,
	                            MAX(evidence_at) AS latest_article_at,
	                            MAX(action_relation_score) AS action_relation_score,
	                            MAX(business_match_score) AS business_match_score,
	                            MAX(text_business_match_score) AS text_business_match_score,
	                            MAX(category_match_score) AS category_match_score,
	                            MAX(is_generic) AS is_generic,
	                            string_agg(DISTINCT business_labels, ', ' ORDER BY business_labels) FILTER (WHERE business_labels IS NOT NULL) AS business_labels,
	                            string_agg(DISTINCT text_business_labels, ', ' ORDER BY text_business_labels) FILTER (WHERE text_business_labels IS NOT NULL) AS text_business_labels,
	                            string_agg(DISTINCT category_labels, ', ' ORDER BY category_labels) FILTER (WHERE category_labels IS NOT NULL) AS category_labels,
                            string_agg(DISTINCT category_relation_labels, ', ' ORDER BY category_relation_labels) FILTER (WHERE category_relation_labels IS NOT NULL) AS category_relation_labels,
                            (array_agg(primary_text_business_label ORDER BY text_business_match_score DESC, evidence_at DESC NULLS LAST) FILTER (WHERE primary_text_business_label IS NOT NULL))[1] AS primary_text_business_label,
                            (array_agg(primary_business_label ORDER BY business_match_score DESC, evidence_at DESC NULLS LAST) FILTER (WHERE primary_business_label IS NOT NULL))[1] AS primary_business_label,
                            (array_agg(primary_category_label ORDER BY category_match_score DESC, evidence_at DESC NULLS LAST) FILTER (WHERE primary_category_label IS NOT NULL))[1] AS primary_category_label,
                            string_agg(DISTINCT action_labels, ', ' ORDER BY action_labels) FILTER (WHERE action_labels IS NOT NULL) AS action_labels
                        FROM evidence_rows
                        GROUP BY company_id, keyword_key
                    ),
                    keyword_spread AS (
                        SELECT
                            keyword_key,
                            SUM(evidence_count) AS total_evidence_count,
                            COUNT(DISTINCT company_id) AS peer_count_with_keyword
                        FROM keyword_agg
                        GROUP BY keyword_key
                    ),
                    scored_keywords AS (
                        SELECT
                            ka.*,
                            ks.total_evidence_count,
                            ks.peer_count_with_keyword,
                            CASE
                                WHEN ks.total_evidence_count > 0
                                THEN ka.evidence_count::double precision / ks.total_evidence_count::double precision
                                ELSE 0
                            END AS company_share,
                            LEAST(3, GREATEST(0, ka.direct_evidence_count - 1)) AS repetition_score,
                            CASE
                                WHEN ka.latest_article_at >= NOW() - INTERVAL '30 days' THEN 1.0
                                WHEN ka.latest_article_at >= NOW() - INTERVAL '90 days' THEN 0.7
                                WHEN ka.latest_article_at >= NOW() - INTERVAL '180 days' THEN 0.4
                                ELSE 0
                            END AS recency_score,
                            LEAST(
                                2,
                                ka.recent_30_count::double precision * 0.6
                                    + GREATEST(0, ka.recent_90_count - ka.recent_30_count)::double precision * 0.25
                            ) AS focus_momentum_score,
                            CASE
                                WHEN ks.total_evidence_count >= 3
                                 AND ka.evidence_count::double precision / ks.total_evidence_count::double precision >= 0.60 THEN 2
                                WHEN ks.total_evidence_count >= 3
                                 AND ka.evidence_count::double precision / ks.total_evidence_count::double precision >= 0.40 THEN 1
                                ELSE 0
                            END AS distinctiveness_score,
                            CASE
                                WHEN ka.text_business_match_score >= 5 THEN 4
                                WHEN ka.text_business_match_score >= 3 THEN 3
                                WHEN ka.business_match_score >= 3 THEN 2
                                WHEN ka.business_match_score > 0 THEN 1
                                WHEN ka.keyword_key LIKE '% %' THEN 1
                                ELSE 0
                            END AS specificity_score,
                            CASE
                                WHEN ka.text_business_match_score >= 5 AND ka.action_relation_score >= 3 THEN 6
                                WHEN ka.text_business_match_score >= 5 THEN 4
                                WHEN ka.text_business_match_score >= 3 AND ka.action_relation_score >= 3 THEN 4
                                WHEN ka.business_match_score >= 3 AND ka.action_relation_score >= 3 THEN 3
                                ELSE 0
                            END AS concrete_activity_score,
                            CASE
                                WHEN ka.is_generic = 1 AND ks.peer_count_with_keyword >= 5 THEN 3
                                WHEN ka.is_generic = 1 THEN 2
                                ELSE 0
                            END AS generic_keyword_penalty
                        FROM keyword_agg ka
                        JOIN keyword_spread ks
                          ON ks.keyword_key = ka.keyword_key
                    ),
                    ranked_keywords AS (
                        SELECT
                            *,
                            ROUND((
                                LN(1 + LEAST(evidence_count, 4))
	                                + LEAST(1.5, evidence_count::double precision / 8.0)
	                                + focus_momentum_score
	                                + concrete_activity_score
	                                + action_relation_score * 0.8
	                                + business_match_score * 0.8
	                                + text_business_match_score * 1.8
	                                + category_match_score * 0.5
                                + repetition_score
                                + recency_score
                                + distinctiveness_score
                                + specificity_score
                                - generic_keyword_penalty
                            )::numeric, 3) AS final_score,
                            ROW_NUMBER() OVER (
                                PARTITION BY company_id
                                ORDER BY
                                    (
                                        LN(1 + LEAST(evidence_count, 4))
	                                        + LEAST(1.5, evidence_count::double precision / 8.0)
	                                        + focus_momentum_score
	                                        + concrete_activity_score
	                                        + action_relation_score * 0.8
	                                        + business_match_score * 0.8
	                                        + text_business_match_score * 1.8
	                                        + category_match_score * 0.5
                                        + repetition_score
                                        + recency_score
                                        + distinctiveness_score
                                        + specificity_score
                                        - generic_keyword_penalty
                                    ) DESC,
	                                    action_relation_score DESC,
	                                    focus_momentum_score DESC,
	                                    business_match_score DESC,
	                                    text_business_match_score DESC,
	                                    category_match_score DESC,
                                    specificity_score DESC,
                                    latest_article_at DESC NULLS LAST,
                                    keyword ASC
                            ) AS keyword_rank
                        FROM scored_keywords
	                        WHERE (business_match_score > 0 OR text_business_match_score > 0 OR category_match_score > 0)
	                          AND recent_90_count > 0
                          AND (
	                              text_business_match_score >= 3
	                              OR business_match_score >= 3
	                              OR (category_match_score > 0 AND action_relation_score >= 3)
	                          )
                          AND generic_keyword_penalty < 4
                    ),
                    evidence_sample_rows AS (
                        SELECT
                            sa.company_id,
                            'selected_axes' AS keyword_key,
                            sa.business_evidence_url AS evidence_url,
                            tp.name || ' 사업 키워드 기준: ' || sa.business_keyword
                            || '. 근거 내용: ' || regexp_replace(sa.business_evidence_text, '[.。]+$', '')
                            || CASE
                                WHEN NULLIF(trim(COALESCE(sa.business_evidence_quote, '')), '') IS NOT NULL
                                THEN '. 원문 확인 문구: ' || regexp_replace(sa.business_evidence_quote, '[.。]+$', '')
                                ELSE ''
                            END
                            || '. 판단 이유: 원문에는 ' || tp.name || '가 '
                            || COALESCE(sa.business_context, sa.business_keyword)
                            || '과 관련된 사업 활동을 진행한다는 내용이 담겨 있습니다. '
                            || CASE
                                WHEN COALESCE(NULLIF(sa.business_action_label, ''), '') <> ''
                                THEN '그 활동이 ' || sa.business_action_label || ' 같은 실행 신호와 함께 나타나 '
                                ELSE '그 활동이 구체적인 실행 흐름으로 나타나 '
                            END
                            || '단순한 기술 소개가 아니라 고객·시장에 제공하려는 사업 방향으로 읽히기 때문에 '''
                            || sa.business_keyword || '''를 사업 키워드로 판단했습니다.' AS evidence_text
                        FROM selected_axes sa
                        JOIN target_peers tp
                          ON tp.id = sa.company_id
                        WHERE sa.business_evidence_text IS NOT NULL
                        UNION ALL
                        SELECT
                            sa.company_id,
                            'selected_axes' AS keyword_key,
                            sa.tech_evidence_url AS evidence_url,
                            tp.name || ' 기술 키워드 기준: ' || sa.tech_keyword
                            || '. 근거 내용: ' || regexp_replace(sa.tech_evidence_text, '[.。]+$', '')
                            || CASE
                                WHEN NULLIF(trim(COALESCE(sa.tech_evidence_quote, '')), '') IS NOT NULL
                                THEN '. 원문 확인 문구: ' || regexp_replace(sa.tech_evidence_quote, '[.。]+$', '')
                                ELSE ''
                            END
                            || '. 판단 이유: 원문에는 ' || tp.name || '가 '
                            || COALESCE(sa.tech_context, sa.tech_keyword)
                            || '을 제품·플랫폼 또는 구현 역량으로 활용한다는 내용이 담겨 있습니다. '
                            || CASE
                                WHEN COALESCE(NULLIF(sa.tech_action_label, ''), '') <> ''
                                THEN '그 내용이 ' || sa.tech_action_label || ' 같은 실행 신호와 함께 나타나 '
                                ELSE '그 내용이 구체적인 적용 흐름으로 나타나 '
                            END
                            || '사업 영역 자체보다 그 사업을 가능하게 하는 기술 축으로 읽히기 때문에 '''
                            || sa.tech_keyword || '''를 기술 키워드로 판단했습니다.' AS evidence_text
                        FROM selected_axes sa
                        JOIN target_peers tp
                          ON tp.id = sa.company_id
                        WHERE sa.tech_evidence_text IS NOT NULL
                    ),
                    evidence_samples AS (
                        SELECT
                            company_id,
                            keyword_key,
                            array_agg(evidence_text ORDER BY evidence_text) AS evidence_texts,
                            array_agg(COALESCE(evidence_url, '') ORDER BY evidence_text) AS evidence_urls
                        FROM evidence_sample_rows
                        GROUP BY company_id, keyword_key
                    )
                    SELECT
                        tp.id,
                        tp.name,
                        tp.ax_revenue_share_pct,
                        NULLIF(concat_ws(E'\n', sa.business_keyword, sa.tech_keyword), '') AS top_keyword,
                        sa.business_keyword,
                        sa.tech_keyword AS technology_keyword,
                        CASE
                            WHEN sa.final_score = 0 THEN '최근 90일 원문 기반 사업 신호에서 통과 조건을 만족한 사업/기술 후보가 없어 '
                                || '핵심 키워드를 노출하지 않습니다.'
                            ELSE '최근 180일 공개 원문 신호의 사업 영역, 신호 유형, 요약, 근거 문장에서 기업이 실제로 추진한 사업 표현과 기술 표현을 분리해 점수화했습니다. '
                                || concat_ws('과 ', sa.business_context, sa.tech_context)
                                || '이 최근 원문 기반 사업 신호와 맞닿아 있는 경우에만 핵심 키워드로 선택했습니다.'
                        END AS top_keyword_reason,
                        CASE
                            WHEN sa.final_score = 0 THEN '최근 원문 기반 사업 신호 통과 후보 없음'
                            ELSE '점수 ' || sa.final_score::text
                                || ' = 사업/기술 후보의 공개 원문 신호 텍스트 매칭 + 실행 활동 + 최근성'
                        END AS top_keyword_basis,
                        NULLIF(sa.final_score, 0) AS top_keyword_score,
                        COALESCE(es.evidence_texts, ARRAY[]::text[]) AS top_keyword_evidence,
                        COALESCE(es.evidence_urls, ARRAY[]::text[]) AS top_keyword_evidence_urls
                    FROM target_peers tp
                    JOIN selected_axes sa
                      ON sa.company_id = tp.id
                    LEFT JOIN evidence_samples es
                      ON es.company_id = tp.id
                     AND es.keyword_key = 'selected_axes'
                    """;

            return jdbcTemplate.query(sql, rs -> {
                Map<String, SupplementalRow> rows = new HashMap<>();
                while (rs.next()) {
                    rows.put(
                            rs.getString("id"),
                            new SupplementalRow(
                                    rs.getString("name"),
                                    nullableDouble(rs.getObject("ax_revenue_share_pct")),
                                    rs.getString("top_keyword"),
                                    rs.getString("business_keyword"),
                                    rs.getString("technology_keyword"),
                                    rs.getString("top_keyword_reason"),
                                    rs.getString("top_keyword_basis"),
                                    nullableDouble(rs.getObject("top_keyword_score")),
                                    nullableStringList(rs.getObject("top_keyword_evidence")),
                                    nullableStringList(rs.getObject("top_keyword_evidence_urls"))
                            )
                    );
                }
                return rows;
            });
        } catch (DataAccessException ex) {
            log.warn("PeerOverviewTable | supplemental rows unavailable, financial rows only", ex);
            return Map.of();
        }
    }

    private Map<String, Object> mapFinancialRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getString("id"));
        row.put("revenueKrwBn", nullableDouble(rs.getObject("revenue_total_krwbn")));
        row.put("revenueQoqPct", nullableDouble(rs.getObject("revenue_qoq_pct")));
        row.put("operatingProfitKrwBn", nullableDouble(rs.getObject("operating_profit_krwbn")));
        row.put("operatingProfitQoqPct", nullableDouble(rs.getObject("operating_profit_qoq_pct")));
        row.put("netIncomeKrwBn", nullableDouble(rs.getObject("net_income_krwbn")));
        row.put("netIncomeQoqPct", nullableDouble(rs.getObject("net_income_qoq_pct")));
        row.put("operatingMarginPct", nullableDouble(rs.getObject("operating_margin_pct")));
        row.put("operatingMarginQoqDeltaPctp", nullableDouble(rs.getObject("operating_margin_qoq_delta_pctp")));
        row.put("dartRceptNo", rs.getString("dart_rcept_no"));
        return row;
    }

    private Map<String, Object> mapPositioningPoint(ResultSet rs, Map<String, DisplayPeer> displayPeerById) throws SQLException {
        String id = rs.getString("id");
        DisplayPeer displayPeer = displayPeerById.get(id);

        Map<String, Object> point = new LinkedHashMap<>();
        point.put("id", id);
        point.put("label", displayPeer == null ? id : displayPeer.label());
        point.put("periodLabel", rs.getString("period"));
        point.put("x", nullableDouble(rs.getObject("revenue_krwbn")));
        point.put("y", nullableDouble(rs.getObject("revenue_yoy_pct")));
        point.put("revenueKrwBn", nullableDouble(rs.getObject("revenue_krwbn")));
        point.put("revenueYoyPct", nullableDouble(rs.getObject("revenue_yoy_pct")));
        point.put("revenueSourceType", rs.getString("revenue_source_type"));
        point.put("revenueYoySourceType", rs.getString("revenue_yoy_source_type"));
        point.put("revenueConfidence", nullableDouble(rs.getObject("revenue_confidence")));
        point.put("revenueYoyConfidence", nullableDouble(rs.getObject("revenue_yoy_confidence")));
        point.put("revenueArticleId", nullableLong(rs.getObject("revenue_article_id")));
        point.put("revenueYoyArticleId", nullableLong(rs.getObject("revenue_yoy_article_id")));
        point.put("isSelf", "sk_ax".equals(id));
        return point;
    }

    private void bindFinancialPeerIds(java.sql.PreparedStatement statement, int startIndex) throws SQLException {
        for (int offset = 0; offset < FINANCIAL_PEER_IDS.size(); offset++) {
            statement.setString(startIndex + offset, FINANCIAL_PEER_IDS.get(offset));
        }
    }

    private Double nullableDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Double doubleValue) {
            return doubleValue;
        }
        if (value instanceof BigDecimal decimalValue) {
            return decimalValue.doubleValue();
        }
        if (value instanceof Number numberValue) {
            return numberValue.doubleValue();
        }
        return null;
    }

    private Long nullableLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Long longValue) {
            return longValue;
        }
        if (value instanceof Integer integerValue) {
            return integerValue.longValue();
        }
        if (value instanceof BigDecimal decimalValue) {
            return decimalValue.longValue();
        }
        if (value instanceof Number numberValue) {
            return numberValue.longValue();
        }
        return null;
    }

    private List<String> nullableStringList(Object value) throws SQLException {
        if (value == null) {
            return List.of();
        }
        if (value instanceof java.sql.Array sqlArray) {
            Object array = sqlArray.getArray();
            if (array instanceof String[] strings) {
                List<String> result = new ArrayList<>();
                for (String item : strings) {
                    if (item != null) {
                        result.add(item);
                    }
                }
                return result;
            }
        }
        return List.of();
    }

    private record DisplayPeer(int displayOrder, String id, String label) {
    }

    private record SupplementalRow(
            String label,
            Double axRevenueSharePct,
            String topKeyword,
            String businessKeyword,
            String technologyKeyword,
            String topKeywordReason,
            String topKeywordBasis,
            Double topKeywordScore,
            List<String> topKeywordEvidence,
            List<String> topKeywordEvidenceUrls
    ) {
    }

    private record CachedPeerOverviewTable(
            Map<String, Object> payload,
            Instant cachedAt
    ) {
        private CachedPeerOverviewTable {
            if (payload == null) {
                payload = Map.of();
            }
            if (cachedAt == null) {
                cachedAt = Instant.EPOCH;
            }
        }
    }

    private Map<String, Object> mapOf(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            map.put((String) entries[i], entries[i + 1]);
        }
        return map;
    }
}
