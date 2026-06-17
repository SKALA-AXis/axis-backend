package com.skala.axis.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.skala.axis.formatter.PeerOverviewFormat.blankToNull;
import static com.skala.axis.formatter.PeerOverviewFormat.firstNonBlank;
import static com.skala.axis.formatter.PeerOverviewFormat.formatKrwBnText;
import static com.skala.axis.formatter.PeerOverviewFormat.formatPercentPointText;
import static com.skala.axis.formatter.PeerOverviewFormat.formatPercentText;
import static com.skala.axis.formatter.PeerOverviewFormat.nullToDash;
import static com.skala.axis.formatter.PeerOverviewFormat.nullToEmpty;
import static com.skala.axis.formatter.PeerOverviewFormat.topicParticle;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
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
    private static final Pattern INTERNAL_EVIDENCE_MARKER = Pattern.compile(
            "\\b(?:raw_article_business_signals|raw_articles|peer_llm_analysis_snapshots|peer_companies|"
                    + "business_area|signal_type|raw_article_id|source_signal_ids|source_raw_article_ids|"
                    + "evidence_refs|evidence_id|signal_id|profile_context|input_snapshot|output_payload|"
                    + "top_keyword_evidence|top_keyword_reason|peer_id)\\b|signal:\\d+",
            Pattern.CASE_INSENSITIVE
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Value("${axis.peer-overview.table-cache-ttl-seconds:600}")
    private long tableCacheTtlSeconds;

    private volatile CachedPeerOverviewTable cachedPeerOverviewTable = new CachedPeerOverviewTable(Map.of(), Instant.EPOCH, Instant.EPOCH);

    public Map<String, Object> getPeerOverviewTable() {
        Instant latestDataVersion = loadLatestPeerOverviewDataVersion();
        CachedPeerOverviewTable current = cachedPeerOverviewTable;
        if (!isPeerOverviewTableCacheExpired(current.cachedAt()) && !isPeerOverviewTableDataStale(current, latestDataVersion)) {
            return current.payload();
        }

        synchronized (this) {
            latestDataVersion = loadLatestPeerOverviewDataVersion();
            current = cachedPeerOverviewTable;
            if (!isPeerOverviewTableCacheExpired(current.cachedAt()) && !isPeerOverviewTableDataStale(current, latestDataVersion)) {
                return current.payload();
            }

            Map<String, Object> refreshed = loadPeerOverviewTable(latestDataVersion);
            cachedPeerOverviewTable = new CachedPeerOverviewTable(refreshed, Instant.now(), latestDataVersion);
            return refreshed;
        }
    }

    private Map<String, Object> loadPeerOverviewTable(Instant dataUpdatedAt) {
        String period = resolveCommonPeriod();
        Map<String, SupplementalRow> supplementalRows = loadLlmKeywordRows(period);
        List<Map<String, Object>> rows = loadRows(period, supplementalRows);
        Map<String, PeerLlmAnalysisSnapshot> llmSnapshots = loadPeerLlmAnalysisSnapshots();
        Map<String, List<Map<String, String>>> comparisonInsights = buildComparisonInsights(rows);
        Map<String, List<Map<String, String>>> swotInsights = defaultSwotInsights();
        Map<String, List<Map<String, Object>>> analysisTraces = new LinkedHashMap<>();
        applyPeerLlmSnapshots(llmSnapshots, comparisonInsights, swotInsights, analysisTraces);
        return mapOf(
                "periodLabel", period,
                "coverageLabel", period == null ? "공통 분기 미확보" : "SK AX · 삼성 SDS · LG CNS · 현대 오토에버 · 포스코 DX 공통 분기 기준",
                "financialSourceLabel", "각 사 IR·사업보고서 기반",
                "supplementalSourceLabel", supplementalRows.isEmpty()
                        ? "LLM 키워드 스냅샷 미확보, 미확보 시 -"
                        : "LLM 키워드 스냅샷 기반, 미확보 시 -",
                "comparisonInsights", comparisonInsights,
                "swotInsights", swotInsights,
                "analysisTraces", analysisTraces,
                "dataUpdatedAt", formatDataUpdatedAt(dataUpdatedAt),
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

    private boolean isPeerOverviewTableDataStale(CachedPeerOverviewTable current, Instant latestDataVersion) {
        if (latestDataVersion == null || Instant.EPOCH.equals(latestDataVersion)) {
            return false;
        }
        Instant cachedDataVersion = current.dataVersion();
        return cachedDataVersion == null || latestDataVersion.isAfter(cachedDataVersion);
    }

    private Instant loadLatestPeerOverviewDataVersion() {
        String sql = """
                SELECT COALESCE(
                    MAX(GREATEST(generated_at, created_at, updated_at)),
                    TIMESTAMPTZ 'epoch'
                ) AS latest_at
                FROM peer_llm_analysis_snapshots
                WHERE analysis_type IN ('peer_swot_comparison', 'peer_overview_keywords')
                  AND status = 'active'
                  AND peer_id IN ('all', 'sk_ax', 'samsung_sds', 'lg_cns', 'hyundai_autoever', 'posco_dx')
                  AND (expires_at IS NULL OR expires_at > NOW())
                """;
        try {
            return jdbcTemplate.query(sql, rs -> {
                if (!rs.next()) {
                    return Instant.EPOCH;
                }
                Timestamp latestAt = rs.getTimestamp("latest_at");
                return latestAt == null ? Instant.EPOCH : latestAt.toInstant();
            });
        } catch (DataAccessException ex) {
            log.warn("PeerOverviewTable | latest snapshot version unavailable, using TTL cache only", ex);
            return Instant.EPOCH;
        }
    }

    public Map<String, Object> getPeerPositioningChart() {
        String period = resolvePositioningCommonPeriod();
        boolean mixedPeriods = period == null;
        List<Map<String, Object>> points = mixedPeriods ? loadLatestPositioningPoints() : loadPositioningPoints(period);
        Instant dataUpdatedAt = loadLatestPeerOverviewDataVersion();
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
                "dataUpdatedAt", formatDataUpdatedAt(dataUpdatedAt),
                "points", points
        );
    }

    private String formatDataUpdatedAt(Instant dataUpdatedAt) {
        return dataUpdatedAt == null || Instant.EPOCH.equals(dataUpdatedAt) ? null : dataUpdatedAt.toString();
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

        try {
            return jdbcTemplate.query(
                    sql,
                    ps -> {
                        bindFinancialPeerIds(ps, 1);
                        ps.setInt(FINANCIAL_PEER_IDS.size() + 1, FINANCIAL_PEER_IDS.size());
                    },
                    rs -> rs.next() ? rs.getString("period") : null
            );
        } catch (DataAccessException ex) {
            log.warn("PeerOverviewTable | raw financial metric period unavailable, using peer_financials fallback", ex);
            return resolveCommonPeriodFromPeerFinancials();
        }
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

    private Map<String, List<Map<String, String>>> defaultSwotInsights() {
        Map<String, List<Map<String, String>>> fallback = new LinkedHashMap<>();
        for (DisplayPeer displayPeer : DISPLAY_PEERS) {
            fallback.put(displayPeer.id(), missingSwotInsights());
        }
        fallback.put("all", missingSwotInsights());
        return fallback;
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

    private Map<String, PeerLlmAnalysisSnapshot> loadPeerLlmAnalysisSnapshots() {
        String sql = """
                WITH latest_snapshots AS (
                    SELECT DISTINCT ON (peer_id)
                        peer_id,
                        output_payload::text AS output_payload,
                        analysis_trace::text AS analysis_trace
                    FROM peer_llm_analysis_snapshots
                    WHERE analysis_type = 'peer_swot_comparison'
                      AND status = 'active'
                      AND peer_id IN ('all', 'samsung_sds', 'lg_cns', 'hyundai_autoever', 'posco_dx')
                      AND (
                          (peer_id = 'all' AND scope = 'all' AND comparison_mode = 'overall_competitors_vs_sk_ax')
                          OR
                          (peer_id <> 'all' AND scope = 'company' AND comparison_mode = 'peer_vs_sk_ax')
                      )
                      AND (expires_at IS NULL OR expires_at > NOW())
                    ORDER BY
                        peer_id,
                        updated_at DESC NULLS LAST,
                        generated_at DESC,
                        created_at DESC
                )
                SELECT peer_id, output_payload, analysis_trace
                FROM latest_snapshots
                """;

        try {
            return jdbcTemplate.query(sql, rs -> {
                Map<String, PeerLlmAnalysisSnapshot> snapshots = new LinkedHashMap<>();
                while (rs.next()) {
                    String peerId = rs.getString("peer_id");
                    Map<String, Object> outputPayload = readJsonObject(rs.getString("output_payload"));
                    List<Map<String, Object>> analysisTrace = readJsonList(rs.getString("analysis_trace"));
                    snapshots.put(peerId, new PeerLlmAnalysisSnapshot(peerId, outputPayload, analysisTrace));
                }
                return snapshots;
            });
        } catch (DataAccessException ex) {
            log.warn("PeerOverviewTable | LLM analysis snapshots unavailable", ex);
            return Map.of();
        }
    }

    private void applyPeerLlmSnapshots(
            Map<String, PeerLlmAnalysisSnapshot> snapshots,
            Map<String, List<Map<String, String>>> comparisonInsights,
            Map<String, List<Map<String, String>>> swotInsights,
            Map<String, List<Map<String, Object>>> analysisTraces
    ) {
        for (PeerLlmAnalysisSnapshot snapshot : snapshots.values()) {
            List<Map<String, String>> comparisonItems = extractComparisonPoints(snapshot.outputPayload());
            if (!comparisonItems.isEmpty()) {
                comparisonInsights.put(snapshot.peerId(), comparisonItems);
            }

            List<Map<String, String>> swotItems = extractSwotItems(snapshot.outputPayload());
            if (!swotItems.isEmpty()) {
                swotInsights.put(snapshot.peerId(), swotItems);
            }

            List<Map<String, Object>> traceItems = extractAnalysisTrace(snapshot);
            if (!traceItems.isEmpty()) {
                analysisTraces.put(snapshot.peerId(), traceItems);
            }
        }
    }

    private List<Map<String, String>> extractComparisonPoints(Map<String, Object> outputPayload) {
        List<Map<String, String>> items = new ArrayList<>();
        for (Object rawItem : listValue(outputPayload.get("comparison_points"))) {
            Map<String, Object> item = objectMap(rawItem);
            String label = stringValue(item.get("label"));
            String body = sanitizeObjectivePeerFlowText(stringValue(item.get("body")));
            if (List.of("포지셔닝", "사업 신호", "기술 신호", "리스크").contains(label) && !body.isBlank()) {
                Map<String, String> comparisonItem = insight(label, body);
                String reasoningSummary = sanitizeObjectivePeerFlowText(stringValue(item.get("reasoning_summary")));
                String evidenceSummary = sanitizeObjectivePeerFlowText(stringValue(item.get("evidence_summary")));
                if (!reasoningSummary.isBlank()) {
                    comparisonItem.put("reasoningSummary", reasoningSummary);
                }
                if (!evidenceSummary.isBlank()) {
                    comparisonItem.put("evidenceSummary", evidenceSummary);
                }
                items.add(comparisonItem);
            }
        }
        return items;
    }

    private List<Map<String, String>> extractSwotItems(Map<String, Object> outputPayload) {
        List<Map<String, String>> items = new ArrayList<>();
        List<Object> sourceItems = listValue(outputPayload.get("swot"));
        if (sourceItems.isEmpty()) {
            sourceItems = listValue(outputPayload.get("swot_monitoring_axes"));
        }
        for (Object rawItem : sourceItems) {
            Map<String, Object> item = objectMap(rawItem);
            String label = stringValue(item.get("label"));
            String body = buildSwotDisplayBody(item);
            if (canonicalSwotLabel(label) != null && !body.isBlank()) {
                Map<String, String> swotItem = insight(canonicalSwotLabel(label), body);
                String title = sanitizeObjectivePeerFlowText(firstNonBlank(
                        stringValue(item.get("title")),
                        stringValue(item.get("axis_name")),
                        stringValue(item.get("axisName"))
                ));
                String factorType = stringValue(firstNonBlank(
                        stringValue(item.get("factor_type")),
                        stringValue(item.get("factorType")),
                        defaultSwotFactorType(canonicalSwotLabel(label))
                ));
                String reasoningSummary = sanitizeObjectivePeerFlowText(stringValue(item.get("reasoning_summary")));
                String evidenceSummary = sanitizeObjectivePeerFlowText(stringValue(item.get("evidence_summary")));
                if (!title.isBlank()) {
                    swotItem.put("title", title);
                }
                if (!factorType.isBlank()) {
                    swotItem.put("factorType", factorType);
                }
                if (!reasoningSummary.isBlank()) {
                    swotItem.put("reasoningSummary", reasoningSummary);
                }
                if (!evidenceSummary.isBlank()) {
                    swotItem.put("evidenceSummary", evidenceSummary);
                }
                String checkPoint = buildSwotCheckPoint(item);
                if (!checkPoint.isBlank()) {
                    swotItem.put("checkPoint", checkPoint);
                }
                items.add(swotItem);
            }
        }
        return items;
    }

    private String buildSwotDisplayBody(Map<String, Object> item) {
        String label = canonicalSwotLabel(stringValue(item.get("label")));
        String body = sanitizeObjectivePeerFlowText(stringValue(item.get("body")));
        if (!body.isBlank() && !looksLikeAxisExplanation(body) && !looksLikeGenericSwotDiagnosis(body)) {
            return normalizeSwotDisplayText(body);
        }
        boolean insufficientEvidence = Boolean.parseBoolean(stringValue(item.get("insufficient_evidence")))
                || Boolean.parseBoolean(stringValue(item.get("insufficientEvidence")));
        String axisDefinition = sanitizeObjectivePeerFlowText(firstNonBlank(
                stringValue(item.get("axis_definition")),
                stringValue(item.get("axisDefinition"))
        ));
        String whyMonitor = sanitizeObjectivePeerFlowText(firstNonBlank(
                stringValue(item.get("why_monitor")),
                stringValue(item.get("whyMonitor"))
        ));
        String supportingPattern = sanitizeObjectivePeerFlowText(firstNonBlank(
                stringValue(item.get("supporting_pattern")),
                stringValue(item.get("supportingPattern"))
        ));
        if (insufficientEvidence || isInsufficientSwotText(axisDefinition)) {
            return "판단 근거가 부족합니다.";
        }
        String title = sanitizeObjectivePeerFlowText(firstNonBlank(
                stringValue(item.get("title")),
                stringValue(item.get("axis_name")),
                stringValue(item.get("axisName"))
        ));
        String evidenceSummary = sanitizeObjectivePeerFlowText(stringValue(item.get("evidence_summary")));
        String reasoningSummary = sanitizeObjectivePeerFlowText(stringValue(item.get("reasoning_summary")));
        String diagnosisBody = buildSwotDiagnosisBody(
                label,
                title,
                supportingPattern,
                evidenceSummary,
                reasoningSummary,
                axisDefinition
        );
        if (!diagnosisBody.isBlank()) {
            return normalizeSwotDisplayText(diagnosisBody);
        }
        if (axisDefinition.equals(whyMonitor)) {
            whyMonitor = "";
        }
        String combined = firstNonBlank(joinDisplaySentences(axisDefinition, whyMonitor), supportingPattern);
        if (!combined.isBlank()) {
            return normalizeSwotDisplayText(combined);
        }
        return normalizeSwotDisplayText(sanitizeObjectivePeerFlowText(firstNonBlank(
                stringValue(item.get("axis_name")),
                stringValue(item.get("axisName")),
                stringValue(item.get("title"))
        )));
    }

    private String buildSwotDiagnosisBody(
            String label,
            String title,
            String supportingPattern,
            String evidenceSummary,
            String reasoningSummary,
            String axisDefinition
    ) {
        String detail = firstNonAxisText(supportingPattern, evidenceSummary, reasoningSummary, axisDefinition);
        String subject = firstNonBlank(
                isGenericSwotTitle(title) ? "" : title,
                swotSubjectFromDetail(detail),
                switch (label == null ? "" : label) {
            case "Strength" -> "내부 역량";
            case "Weakness" -> "내부 개선 과제";
            case "Opportunity" -> "외부 성장 기회";
            case "Threat" -> "외부 위협 요인";
            default -> "SWOT 요인";
        });
        String sentence = switch (label == null ? "" : label) {
            case "Strength" -> subject + topicParticle(subject) + " 내부 강점으로 확인됩니다.";
            case "Weakness" -> subject + topicParticle(subject) + " 내부 개선 과제로 남아 있습니다.";
            case "Opportunity" -> subject + topicParticle(subject) + " 외부 기회 요인으로 볼 수 있습니다.";
            case "Threat" -> subject + topicParticle(subject) + " 외부 위협 요인으로 볼 수 있습니다.";
            default -> subject + topicParticle(subject) + " 주요 SWOT 요인으로 볼 수 있습니다.";
        };
        if (!detail.isBlank()) {
            sentence = sentence + " " + detail;
        }
        return sentence;
    }

    private boolean isGenericSwotTitle(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        return List.of(
                "내부 역량",
                "내부 역량 강화",
                "내부 역량 통합성",
                "내부 역량의 반복 적용성",
                "내부 제약",
                "내부 제약 관리",
                "외부 시장 기회",
                "시장 기회",
                "시장 기회 탐색",
                "시장 성장 가능성",
                "외부 환경 압박",
                "외부 환경의 부정적 압박",
                "외부 환경의 유리한 조건",
                "경쟁 및 규제 압박",
                "외부 기회",
                "외부 위협",
                "외부 경쟁 위협",
                "외부 압박 요인",
                "외부 경쟁 압박",
                "수익성 개선",
                "수익성 개선 필요",
                "수익성 개선 필요성",
                "사업 성장 가능성",
                "성장 가능성"
        ).contains(value.trim());
    }

    private String swotSubjectFromDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            return "";
        }
        List<String> patterns = List.of(
                "GPUaaS",
                "패브릭스",
                "클라우드",
                "AI",
                "IT 서비스",
                "스마트 엔지니어링",
                "스마트 물류",
                "제조 AX",
                "차량용 소프트웨어",
                "차량 SW",
                "R&D 비용",
                "CCS",
                "OTA",
                "내비게이션 플랫폼",
                "데이터센터",
                "Intelligent Factory",
                "로봇",
                "공장 자동화",
                "영업이익"
        );
        List<String> matched = new ArrayList<>();
        for (String pattern : patterns) {
            if (detail.contains(pattern) && !matched.contains(pattern)) {
                matched.add(pattern);
            }
            if (matched.size() >= 2) {
                break;
            }
        }
        if (!matched.isEmpty()) {
            return String.join("·", matched);
        }
        Matcher matcher = Pattern.compile("[A-Za-z][A-Za-z0-9+&./-]{2,}|[가-힣0-9A-Za-z+&./-]{3,}").matcher(detail);
        List<String> tokens = new ArrayList<>();
        while (matcher.find() && tokens.size() < 2) {
            String token = matcher.group();
            if (!List.of("관련", "기반", "최근", "변화", "확대", "강화", "관찰됨", "나타나고").contains(token)) {
                tokens.add(token);
            }
        }
        return String.join("·", tokens);
    }

    private String firstNonAxisText(String... values) {
        for (String value : values) {
            String cleaned = sanitizeObjectivePeerFlowText(value);
            if (!cleaned.isBlank() && !looksLikeAxisExplanation(cleaned)) {
                return cleaned;
            }
        }
        return "";
    }

    private boolean looksLikeAxisExplanation(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return value.contains("이 축은")
                || value.contains("관찰 축")
                || value.contains("판단하기 위한 기준")
                || value.contains("판단 기준")
                || value.contains("추적해야")
                || value.contains("모니터링");
    }

    private boolean looksLikeGenericSwotDiagnosis(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String cleaned = sanitizeObjectivePeerFlowText(value);
        boolean hasGenericFrame = cleaned.contains("내부 강점으로 확인")
                || cleaned.contains("내부 개선 과제로 남")
                || cleaned.contains("외부 기회 요인으로 볼 수")
                || cleaned.contains("외부 위협 요인으로 볼 수");
        if (!hasGenericFrame) {
            return false;
        }
        return cleaned.startsWith("내부 역량")
                || cleaned.startsWith("내부 제약")
                || cleaned.startsWith("외부 시장")
                || cleaned.startsWith("시장 기회")
                || cleaned.startsWith("외부 환경")
                || cleaned.startsWith("경쟁 및 규제")
                || cleaned.startsWith("수익성 개선")
                || cleaned.startsWith("시장 성장");
    }

    private String buildSwotCheckPoint(Map<String, Object> item) {
        String explicit = sanitizeObjectivePeerFlowText(firstNonBlank(
                stringValue(item.get("check_point")),
                stringValue(item.get("checkPoint"))
        ));
        if (!explicit.isBlank()) {
            return explicit;
        }
        String watchVariables = flattenSwotValue(firstNonBlankObject(
                item.get("watch_variables"),
                item.get("watchVariables")
        ));
        return sanitizeObjectivePeerFlowText(watchVariables);
    }

    private String joinNonBlank(String... values) {
        List<String> parts = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                parts.add(value);
            }
        }
        return String.join(" ", parts);
    }

    private String joinDisplaySentences(String... values) {
        List<String> parts = new ArrayList<>();
        for (String value : values) {
            String cleaned = sanitizeObjectivePeerFlowText(value);
            if (cleaned.isBlank()) {
                continue;
            }
            if (!parts.isEmpty()) {
                int lastIndex = parts.size() - 1;
                String previous = parts.get(lastIndex);
                if (!previous.endsWith(".") && !previous.endsWith("!") && !previous.endsWith("?") && !previous.endsWith("。")) {
                    parts.set(lastIndex, previous + ".");
                }
            }
            parts.add(cleaned);
        }
        return String.join(" ", parts);
    }

    private boolean isInsufficientSwotText(String value) {
        return value != null && (
                value.contains("현재 입력 근거만으로 해당 축을 정의하기 어렵다")
                        || value.contains("판단 근거 부족")
        );
    }

    private String normalizeSwotDisplayText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String cleaned = sanitizeObjectivePeerFlowText(value)
                .replaceAll("[\\[\\]\\{\\}\"]", " ")
                .replaceAll("(?m)^\\s*[-*•]\\s*", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.isBlank()) {
            return "";
        }

        List<String> uniqueSentences = new ArrayList<>();
        for (String sentence : cleaned.split("(?<=[.!?。])\\s+")) {
            String normalized = sentence.replaceAll("\\s+", " ").trim();
            if (!normalized.isBlank() && !uniqueSentences.contains(normalized)) {
                uniqueSentences.add(normalized);
            }
        }
        return String.join(" ", uniqueSentences);
    }

    private Object firstNonBlankObject(Object... values) {
        for (Object value : values) {
            String text = stringValue(value);
            if (!text.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String defaultSwotFactorType(String label) {
        if (label == null) {
            return "";
        }
        return switch (label) {
            case "Strength", "Weakness" -> "internal_controllable";
            case "Opportunity", "Threat" -> "external_uncontrollable";
            default -> "";
        };
    }

    private List<Map<String, Object>> extractAnalysisTrace(PeerLlmAnalysisSnapshot snapshot) {
        List<Map<String, Object>> items = new ArrayList<>();
        List<Map<String, Object>> sourceItems = snapshot.analysisTrace().isEmpty()
                ? objectList(snapshot.outputPayload().get("analysis_trace"))
                : snapshot.analysisTrace();
        for (Map<String, Object> item : sourceItems) {
            String step = stringValue(item.get("step"));
            String summary = sanitizeObjectivePeerFlowText(stringValue(item.get("summary")));
            String reasoning = sanitizeObjectivePeerFlowText(firstNonBlank(
                    stringValue(item.get("reasoning")),
                    stringValue(item.get("reasoning_summary")),
                    stringValue(item.get("interpretation"))
            ));
            String evidence = sanitizeObjectivePeerFlowText(firstNonBlank(
                    stringValue(item.get("evidence")),
                    stringValue(item.get("evidence_summary")),
                    stringValue(item.get("basis")),
                    stringValue(item.get("source_summary"))
            ));
            if (!step.isBlank() && (!summary.isBlank() || !reasoning.isBlank() || !evidence.isBlank())) {
                items.add(traceItem(step, summary, reasoning, evidence));
            }
        }
        return items;
    }

    private Map<String, Object> readJsonObject(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() {
            });
        } catch (Exception ex) {
            log.warn("PeerOverviewTable | failed to parse LLM output payload", ex);
            return Map.of();
        }
    }

    private List<Map<String, Object>> readJsonList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() {
            });
        } catch (Exception ex) {
            log.warn("PeerOverviewTable | failed to parse LLM analysis trace", ex);
            return List.of();
        }
    }

    private List<Object> listValue(Object value) {
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return List.of();
    }

    private List<Map<String, Object>> objectList(Object value) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object rawItem : listValue(value)) {
            Map<String, Object> item = objectMap(rawItem);
            if (!item.isEmpty()) {
                items.add(item);
            }
        }
        return items;
    }

    private Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }
        return Map.of();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String sanitizeObjectivePeerFlowText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value
                .replace("최근 공개 원문에서는", "")
                .replace("최근 공개 원문에서", "최근 신호에서")
                .replace("최근 공개 원문 신호", "최근 신호")
                .replace("이 Peer사는", "해당 기업은")
                .replace("이 Peer사가", "해당 기업이")
                .replace("이 Peer사를", "해당 기업을")
                .replace("이 Peer사의", "해당 기업의")
                .replace("이 peer사는", "해당 기업은")
                .replace("이 peer사가", "해당 기업이")
                .replace("이 peer사를", "해당 기업을")
                .replace("이 peer사의", "해당 기업의")
                .replace("Peer사는", "해당 기업은")
                .replace("Peer사가", "해당 기업이")
                .replace("Peer사를", "해당 기업을")
                .replace("Peer사의", "해당 기업의")
                .replace("Peer사에", "해당 기업에")
                .replace("Peer사", "대상 기업")
                .replace("peer사는", "해당 기업은")
                .replace("peer사가", "해당 기업이")
                .replace("peer사를", "해당 기업을")
                .replace("peer사의", "해당 기업의")
                .replace("peer사에", "해당 기업에")
                .replace("peer사", "대상 기업")
                .replace("현재 입력 근거만으로 해당 축을 정의하기 어렵다", "판단 근거가 부족합니다.")
                .replace("SK AX와 비교했을 때", "")
                .replace("SK AX와 비교해", "")
                .replace("SK AX와 비교하면", "")
                .replace("SK AX 대비", "")
                .replace("SK AX 기준", "")
                .replace("SK AX 관점에서", "")
                .replace("SK AX는", "해당 기업은")
                .replace("SK AX의", "해당 기업의")
                .replace("자사", "해당 기업")
                .replaceAll("\\s+", " ")
                .trim();
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
                insight("사업 신호", "최근 신호를 보면 " + businessSummary + " 관련 활동이 핵심 사업 차이로 나타납니다. 각 기업의 사업명과 고객 산업이 분기별 진행 방향을 보여주는 객관 지표로 쓰입니다."),
                insight("기술 신호", "기술 축에서는 " + techSummary + " 흐름이 보입니다. 제품·플랫폼·구현 역량이 각 peer의 기술 방향을 구분하는 기준입니다."),
                insight("리스크", "Peer를 비교할 때는 최근 사업 신호와 공시 수치를 함께 봐야 합니다. 현재 수익성 기준으로는 " + marginLeader + "가 두드러지며, 세부 부문 공시 범위 차이는 비교 리스크로 남아 있습니다.")
        );
    }

    private List<Map<String, String>> buildPeerComparisonInsights(Map<String, Object> skAxRow, Map<String, Object> peerRow) {
        String peerLabel = String.valueOf(peerRow.get("label"));
        String peerBusiness = nullToDash(peerRow.get("businessKeyword"));
        String peerTech = nullToDash(peerRow.get("technologyKeyword"));
        Double revenue = nullableDouble(peerRow.get("revenueKrwBn"));
        Double margin = nullableDouble(peerRow.get("operatingMarginPct"));
        Double marginDelta = nullableDouble(peerRow.get("operatingMarginQoqDeltaPctp"));

        return List.of(
                insight("포지셔닝", peerLabel + "는 사업 신호의 " + peerBusiness + ", 기술 신호의 " + peerTech + "를 앞세우는 흐름으로 읽힙니다. 분기별 사업명과 기술명이 확인해야 할 초점을 나눕니다."),
                insight("사업 신호", peerLabel + "는 최근 신호에서 " + peerBusiness + " 관련 사업 활동이 가장 강하게 읽힙니다. 수주·확장·고객 산업 같은 실행 신호가 사업 방향 판단의 기준입니다."),
                insight("기술 신호", peerLabel + "는 " + peerTech + "를 제품·플랫폼 또는 구현 역량의 중심으로 보여줍니다. 기술명과 플랫폼 신호가 기술 방향의 객관 지표로 쓰입니다."),
                insight("리스크", buildRiskInsight(peerLabel, revenue, margin, marginDelta))
        );
    }

    private Map<String, String> insight(String label, String body) {
        Map<String, String> item = new LinkedHashMap<>();
        item.put("label", label);
        item.put("body", body);
        return item;
    }

    private Map<String, Object> traceItem(String label, String body, String reasoning, String evidence) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("label", label);
        item.put("body", body);
        if (reasoning != null && !reasoning.isBlank()) {
            item.put("reasoning", reasoning);
        }
        if (evidence != null && !evidence.isBlank()) {
            item.put("evidence", evidence);
        }
        return item;
    }

    private String buildRiskInsight(String peerLabel, Double revenue, Double margin, Double marginDelta) {
        String revenueText = revenue == null ? "매출 데이터가 제한적" : "매출 " + formatKrwBnText(revenue);
        String marginText = margin == null ? "영업이익률 데이터가 제한적" : "영업이익률 " + formatPercentText(margin);
        String deltaText = marginDelta == null ? "전분기 대비 수익성 변화는 확인이 제한적입니다" : "전분기 대비 영업이익률 변화는 " + formatPercentPointText(marginDelta) + "입니다";
        return peerLabel + "는 " + revenueText + ", " + marginText + " 기준으로 함께 봐야 합니다. " + deltaText + ". 따라서 최근 사업·기술 신호가 강하더라도 실적 범위와 수익성 변동은 별도 리스크로 남습니다.";
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

        try {
            return jdbcTemplate.query(
                    sql,
                    ps -> {
                        bindFinancialPeerIds(ps, 1);
                        ps.setString(FINANCIAL_PEER_IDS.size() + 1, period);
                    },
                    (rs, rowNum) -> mapFinancialRow(rs)
            );
        } catch (DataAccessException ex) {
            log.warn("PeerOverviewTable | raw financial rows unavailable, using peer_financials fallback", ex);
            return loadPeerFinancialRows(period);
        }
    }

    private String resolveCommonPeriodFromPeerFinancials() {
        String sql = """
                SELECT period
                FROM peer_financials
                WHERE peer_id IN (?, ?, ?, ?, ?)
                  AND period ~ '^[0-9]{4}Q[1-4]$'
                  AND revenue_total_krwbn IS NOT NULL
                  AND operating_profit_krwbn IS NOT NULL
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

    private List<Map<String, Object>> loadPeerFinancialRows(String period) {
        if (period == null || period.isBlank()) {
            return List.of();
        }

        String sql = """
                WITH normalized_periods AS (
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
                        CASE
                            WHEN raw_payload ? 'operating_margin_pct'
                            THEN ROUND((raw_payload ->> 'operating_margin_pct')::numeric, 2)::double precision
                            WHEN revenue_total_krwbn IS NOT NULL
                             AND revenue_total_krwbn <> 0
                             AND operating_profit_krwbn IS NOT NULL
                            THEN ROUND((operating_profit_krwbn / revenue_total_krwbn * 100)::numeric, 2)::double precision
                            ELSE NULL
                        END AS operating_margin_pct,
                        dart_rcept_no
                    FROM peer_financials
                    WHERE peer_id IN (?, ?, ?, ?, ?)
                      AND period ~ '^[0-9]{4}Q[1-4]$'
                ),
                period_series AS (
                    SELECT
                        current_period.peer_id,
                        current_period.revenue_total_krwbn,
                        current_period.operating_profit_krwbn,
                        current_period.operating_margin_pct,
                        current_period.dart_rcept_no,
                        previous_period.revenue_total_krwbn AS prev_revenue_total_krwbn,
                        previous_period.operating_profit_krwbn AS prev_operating_profit_krwbn,
                        previous_period.operating_margin_pct AS prev_operating_margin_pct
                    FROM normalized_periods current_period
                    LEFT JOIN normalized_periods previous_period
                      ON previous_period.peer_id = current_period.peer_id
                     AND previous_period.period = current_period.previous_period
                    WHERE current_period.period = ?
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
                    NULL::double precision AS net_income_krwbn,
                    NULL::numeric AS net_income_qoq_pct,
                    operating_margin_pct,
                    CASE
                        WHEN prev_operating_margin_pct IS NOT NULL
                         AND operating_margin_pct IS NOT NULL
                        THEN ROUND((operating_margin_pct - prev_operating_margin_pct)::numeric, 2)
                        ELSE NULL
                    END AS operating_margin_qoq_delta_pctp,
                    dart_rcept_no
                FROM period_series
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

    private Map<String, SupplementalRow> loadSupplementalRows(String period) {
        try {
            String sql = """
                    WITH target_peers AS (
                        SELECT
                            pc.id,
                            pc.name,
                            pc.ax_revenue_share_pct
                        FROM peer_companies pc
                        WHERE pc.id IN ('sk_ax', 'samsung_sds', 'lg_cns', 'hyundai_autoever', 'posco_dx')
                    ),
                    selected_signal_period AS (
                        SELECT COALESCE(
                            NULLIF(?, ''),
                            (
                                SELECT rabs.period
                                FROM raw_article_business_signals rabs
                                WHERE rabs.peer_id IN (SELECT id FROM target_peers)
                                  AND rabs.period ~ '^[0-9]{4}Q[1-4]$'
                                GROUP BY rabs.period
                                ORDER BY
                                    MAX(COALESCE(rabs.period_year, NULLIF(SUBSTRING(rabs.period FROM '^([0-9]{4})'), '')::int, 0)) DESC,
                                    MAX(COALESCE(rabs.period_quarter, NULLIF(SUBSTRING(rabs.period FROM 'Q([1-4])$'), '')::int, 0)) DESC,
                                    rabs.period DESC
                                LIMIT 1
                            )
                        ) AS period
                    ),
                    blocked_keywords(keyword_key) AS (
                        VALUES
                            ('contract'),
                            ('tech_release'),
                            ('other'),
                            ('market_trend'),
                            ('market'),
                            ('financial'),
                            ('company'),
                            ('personnel'),
                            ('tech'),
                            ('regulation'),
                            ('partnership'),
                            ('expansion'),
                            ('deal'),
                            ('ma'),
                            ('infra'),
                            ('security'),
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
                            ('삼성sds'),
                            ('lgcns'),
                            ('현대오토에버'),
                            ('포스코dx'),
                            ('ai'),
                            ('ax'),
                            ('dx'),
                            ('ai/dx'),
                            ('ai·dx'),
                            ('ai dx'),
                            ('기술')
                    ),
                    action_terms(term, weight, label, evidence_priority) AS (
                        VALUES
                            ('수주', 4, '수주', 10),
                            ('계약', 4, '계약', 10),
                            ('담당', 3, '담당', 8),
                            ('체결', 4, '체결', 10),
                            ('선정', 4, '선정', 10),
                            ('착수', 3, '착수', 8),
                            ('실증', 3, '실증', 8),
                            ('출시', 3, '출시', 8),
                            ('투자', 3, '투자', 8),
                            ('공급', 3, '공급', 8),
                            ('납품', 3, '납품', 8),
                            ('상용화', 3, '상용화', 8),
                            ('적용', 2, '적용', 6),
                            ('도입', 2, '도입', 6),
                            ('구축', 3, '구축', 8),
                            ('개발', 2, '개발', 6),
                            ('고도화', 2, '고도화', 6),
                            ('운영', 2, '운영', 6),
                            ('확대', 2, '확대', 6),
                            ('협력', 2, '협력', 6),
                            ('제휴', 2, '제휴', 6),
                            ('제공', 2, '제공', 6)
                    ),
                    technology_indicators(term, weight) AS (
                        VALUES
                            ('생성형ai', 5),
                            ('생성ai', 5),
                            ('ai에이전트', 5),
                            ('에이전트', 4),
                            ('llm', 5),
                            ('sllm', 5),
                            ('rag', 4),
                            ('mlops', 4),
                            ('aiops', 4),
                            ('클라우드', 3),
                            ('cloud', 3),
                            ('데이터센터', 3),
                            ('인프라', 2),
                            ('보안', 3),
                            ('제로트러스트', 4),
                            ('로봇', 4),
                            ('로보틱스', 4),
                            ('휴머노이드', 5),
                            ('디지털트윈', 4),
                            ('스마트팩토리', 3),
                            ('자율주행', 4),
                            ('ota', 4),
                            ('커넥티드카', 4),
                            ('내비게이션', 3),
                            ('플랫폼', 2),
                            ('솔루션', 2),
                            ('자동화', 3),
                            ('데이터', 2),
                            ('모델', 2),
                            ('소프트웨어', 3),
                            ('sw', 3),
                            ('ai', 1),
                            ('dx', 1),
                            ('ax', 1)
                    ),
                    signal_rows AS (
                        SELECT
                            rabs.id AS card_id,
                            rabs.peer_id AS company_id,
                            rabs.business_area,
                            rabs.signal_type,
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
                            NULLIF(ra.url, '') AS evidence_url,
                            COALESCE(rabs.confidence, 0.5) AS confidence,
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
                            ), '\\s+', '', 'g') AS evidence_compact
                        FROM raw_article_business_signals rabs
                        JOIN raw_articles ra
                          ON ra.id = rabs.raw_article_id
                        JOIN selected_signal_period ssp
                          ON ssp.period = rabs.period
                        WHERE rabs.peer_id IN (SELECT id FROM target_peers)
                    ),
                    candidate_rows AS (
                        SELECT
                            sr.*,
                            'business' AS axis_type,
                            trim(sr.business_area) AS keyword
                        FROM signal_rows sr
                        WHERE NULLIF(trim(COALESCE(sr.business_area, '')), '') IS NOT NULL
                        UNION ALL
                        SELECT
                            sr.*,
                            'tech' AS axis_type,
                            trim(keyword_value) AS keyword
                        FROM signal_rows sr
                        CROSS JOIN LATERAL (
                            SELECT sr.business_area AS keyword_value
                            UNION ALL
                            SELECT sr.signal_type AS keyword_value
                        ) keywords
                        WHERE NULLIF(trim(COALESCE(keyword_value, '')), '') IS NOT NULL
                    ),
                    normalized_candidates AS (
                        SELECT DISTINCT
                            card_id,
                            company_id,
                            axis_type,
                            keyword,
                            lower(regexp_replace(trim(keyword), '\\s+', ' ', 'g')) AS keyword_key,
                            regexp_replace(lower(trim(keyword)), '\\s+', '', 'g') AS keyword_compact,
                            evidence_text,
                            evidence_quote,
                            article_content,
                            evidence_url,
                            confidence,
                            text_norm,
                            text_compact,
                            evidence_compact
                        FROM candidate_rows
                        WHERE length(trim(keyword)) >= 2
                    ),
                    scored_candidate_rows AS (
                        SELECT
                            nc.*,
                            COALESCE(MAX(ti.weight), 0) AS technology_match_score,
                            COALESCE(MAX(at.weight), 0) AS action_relation_score,
                            (array_agg(at.label ORDER BY at.evidence_priority DESC, length(at.term) DESC) FILTER (
                                WHERE at.label IS NOT NULL
                                  AND nc.evidence_compact LIKE '%' || regexp_replace(lower(at.term), '\\s+', '', 'g') || '%'
                            ))[1] AS primary_action_label,
                            CASE WHEN bk.keyword_key IS NOT NULL THEN 1 ELSE 0 END AS is_blocked
                        FROM normalized_candidates nc
                        LEFT JOIN technology_indicators ti
                          ON nc.keyword_compact LIKE '%' || ti.term || '%'
                          OR nc.text_compact LIKE '%' || ti.term || '%'
                        LEFT JOIN action_terms at
                          ON nc.text_compact LIKE '%' || regexp_replace(lower(at.term), '\\s+', '', 'g') || '%'
                        LEFT JOIN blocked_keywords bk
                          ON bk.keyword_key = nc.keyword_key
                        GROUP BY
                            nc.card_id,
                            nc.company_id,
                            nc.axis_type,
                            nc.keyword,
                            nc.keyword_key,
                            nc.keyword_compact,
                            nc.evidence_text,
                            nc.evidence_quote,
                            nc.article_content,
                            nc.evidence_url,
                            nc.confidence,
                            nc.text_norm,
                            nc.text_compact,
                            nc.evidence_compact,
                            bk.keyword_key
                    ),
                    axis_keyword_agg AS (
                        SELECT
                            company_id,
                            axis_type,
                            keyword_key,
                            (array_agg(keyword ORDER BY length(keyword) DESC, keyword ASC))[1] AS label,
                            COUNT(DISTINCT card_id) AS evidence_count,
                            MAX(technology_match_score) AS technology_match_score,
                            MAX(action_relation_score) AS action_relation_score,
                            AVG(confidence) AS confidence_score,
                            (array_agg(primary_action_label ORDER BY action_relation_score DESC) FILTER (
                                WHERE primary_action_label IS NOT NULL
                            ))[1] AS primary_action_label,
                            (array_agg(evidence_text ORDER BY action_relation_score DESC, confidence DESC, length(evidence_text) DESC) FILTER (
                                WHERE evidence_text IS NOT NULL
                            ))[1] AS selected_evidence_text,
                            (array_agg(evidence_url ORDER BY action_relation_score DESC, confidence DESC) FILTER (
                                WHERE evidence_url IS NOT NULL
                            ))[1] AS selected_evidence_url,
                            (array_agg(
                                NULLIF(trim(regexp_replace(
                                    CASE
                                        WHEN position(keyword_compact in regexp_replace(lower(COALESCE(article_content, '')), '\\s+', '', 'g')) > 0
                                        THEN substring(article_content FROM 1 FOR 260)
                                        ELSE COALESCE(evidence_quote, evidence_text)
                                    END,
                                    '\\s+',
                                    ' ',
                                    'g'
                                )), '')
                                ORDER BY action_relation_score DESC, confidence DESC
                            ) FILTER (WHERE COALESCE(evidence_quote, evidence_text, article_content) IS NOT NULL))[1] AS selected_evidence_quote
                        FROM scored_candidate_rows
                        WHERE is_blocked = 0
                          AND (
                            axis_type = 'business'
                            OR technology_match_score >= 2
                          )
                        GROUP BY company_id, axis_type, keyword_key
                    ),
                    ranked_axes AS (
                        SELECT
                            *,
                            ROUND((
                                LN(1 + LEAST(evidence_count, 6))
                                + LEAST(2.0, evidence_count::double precision / 3.0)
                                + action_relation_score * 0.8
                                + CASE WHEN axis_type = 'tech' THEN technology_match_score * 0.9 ELSE 0 END
                                + LEAST(1.0, confidence_score)
                                + CASE WHEN length(label) >= 5 THEN 0.6 ELSE 0 END
                            )::numeric, 3) AS final_score,
                            ROW_NUMBER() OVER (
                                PARTITION BY company_id, axis_type
                                ORDER BY
                                    (
                                        LN(1 + LEAST(evidence_count, 6))
                                        + LEAST(2.0, evidence_count::double precision / 3.0)
                                        + action_relation_score * 0.8
                                        + CASE WHEN axis_type = 'tech' THEN technology_match_score * 0.9 ELSE 0 END
                                        + LEAST(1.0, confidence_score)
                                        + CASE WHEN length(label) >= 5 THEN 0.6 ELSE 0 END
                                    ) DESC,
                                    action_relation_score DESC,
                                    technology_match_score DESC,
                                    evidence_count DESC,
                                    length(label) DESC,
                                    label ASC
                            ) AS axis_rank
                        FROM axis_keyword_agg
                        WHERE evidence_count > 0
                    ),
                    selected_axes AS (
                        SELECT
                            tp.id AS company_id,
                            MAX(ra.label) FILTER (WHERE ra.axis_type = 'business' AND ra.axis_rank = 1) AS business_keyword,
                            MAX(ra.label) FILTER (WHERE ra.axis_type = 'tech' AND ra.axis_rank = 1) AS tech_keyword,
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
                        LEFT JOIN ranked_axes ra
                          ON ra.company_id = tp.id
                         AND ra.axis_rank = 1
                        GROUP BY tp.id
                    ),
                    evidence_sample_rows AS (
                        SELECT
                            sa.company_id,
                            sa.business_evidence_url AS evidence_url,
                            tp.name || ' 사업 키워드 기준: ' || sa.business_keyword
                            || '. 근거 내용: ' || regexp_replace(sa.business_evidence_text, '[.。]+$', '')
                            || CASE
                                WHEN NULLIF(trim(COALESCE(sa.business_evidence_quote, '')), '') IS NOT NULL
                                THEN '. 원문 확인 문구: ' || regexp_replace(sa.business_evidence_quote, '[.。]+$', '')
                                ELSE ''
                            END
                            || '. 판단 이유: ' || COALESCE((SELECT period FROM selected_signal_period), '해당 분기')
                            || ' 원문 기반 사업 신호의 business_area에서 반복 확인된 표현입니다. '
                            || CASE
                                WHEN COALESCE(NULLIF(sa.business_action_label, ''), '') <> ''
                                THEN sa.business_action_label || ' 같은 실행 신호가 함께 나타나 '
                                ELSE '구체적인 사업 문맥과 함께 나타나 '
                            END
                            || '해당 분기 사업 방향으로 읽히기 때문에 '''
                            || sa.business_keyword || '''를 사업 키워드로 판단했습니다.' AS evidence_text
                        FROM selected_axes sa
                        JOIN target_peers tp
                          ON tp.id = sa.company_id
                        WHERE sa.business_evidence_text IS NOT NULL
                          AND sa.business_keyword IS NOT NULL
                        UNION ALL
                        SELECT
                            sa.company_id,
                            sa.tech_evidence_url AS evidence_url,
                            tp.name || ' 기술 키워드 기준: ' || sa.tech_keyword
                            || '. 근거 내용: ' || regexp_replace(sa.tech_evidence_text, '[.。]+$', '')
                            || CASE
                                WHEN NULLIF(trim(COALESCE(sa.tech_evidence_quote, '')), '') IS NOT NULL
                                THEN '. 원문 확인 문구: ' || regexp_replace(sa.tech_evidence_quote, '[.。]+$', '')
                                ELSE ''
                            END
                            || '. 판단 이유: ' || COALESCE((SELECT period FROM selected_signal_period), '해당 분기')
                            || ' 원문 기반 사업 신호에서 일반 기술 신호와 함께 확인된 표현입니다. '
                            || CASE
                                WHEN COALESCE(NULLIF(sa.tech_action_label, ''), '') <> ''
                                THEN sa.tech_action_label || ' 같은 실행 신호가 함께 나타나 '
                                ELSE '구체적인 적용 문맥과 함께 나타나 '
                            END
                            || '사업을 가능하게 하는 기술 축으로 읽히기 때문에 '''
                            || sa.tech_keyword || '''를 기술 키워드로 판단했습니다.' AS evidence_text
                        FROM selected_axes sa
                        JOIN target_peers tp
                          ON tp.id = sa.company_id
                        WHERE sa.tech_evidence_text IS NOT NULL
                          AND sa.tech_keyword IS NOT NULL
                    ),
                    evidence_samples AS (
                        SELECT
                            company_id,
                            array_agg(evidence_text ORDER BY evidence_text) AS evidence_texts,
                            array_agg(COALESCE(evidence_url, '') ORDER BY evidence_text) AS evidence_urls
                        FROM evidence_sample_rows
                        GROUP BY company_id
                    )
                    SELECT
                        tp.id,
                        tp.name,
                        tp.ax_revenue_share_pct,
                        NULLIF(concat_ws(E'\\n', sa.business_keyword, sa.tech_keyword), '') AS top_keyword,
                        sa.business_keyword,
                        sa.tech_keyword AS technology_keyword,
                        CASE
                            WHEN sa.final_score = 0 THEN COALESCE((SELECT period FROM selected_signal_period), '해당 분기')
                                || ' 원문 기반 사업 신호에서 통과 후보가 없어 핵심 키워드를 노출하지 않습니다.'
                            ELSE COALESCE((SELECT period FROM selected_signal_period), '해당 분기')
                                || ' 공개 원문의 제목·요약·근거 문장에서 실제 등장한 사업명, 서비스명, 제품명, 기술명을 기준으로 점수화했습니다. '
                                || '해당 분기에 확인된 사업 표현과 기술 신호를 분리해 선택했습니다.'
                        END AS top_keyword_reason,
                        CASE
                            WHEN sa.final_score = 0 THEN '분기 원문 기반 사업 신호 통과 후보 없음'
                            ELSE '점수 ' || sa.final_score::text
                                || ' = 분기 내 원문 신호 빈도 + 실행 활동 + 일반 기술 신호 + 추출 신뢰도'
                        END AS top_keyword_basis,
                        NULLIF(sa.final_score, 0) AS top_keyword_score,
                        COALESCE(es.evidence_texts, ARRAY[]::text[]) AS top_keyword_evidence,
                        COALESCE(es.evidence_urls, ARRAY[]::text[]) AS top_keyword_evidence_urls
                    FROM target_peers tp
                    JOIN selected_axes sa
                      ON sa.company_id = tp.id
                    LEFT JOIN evidence_samples es
                      ON es.company_id = tp.id
                    """;

            return jdbcTemplate.query(
                    sql,
                    ps -> ps.setString(1, period),
                    rs -> {
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
                    }
            );
        } catch (DataAccessException ex) {
            log.warn("PeerOverviewTable | quarterly supplemental rows unavailable, financial rows only", ex);
            return Map.of();
        }
    }

    private Map<String, SupplementalRow> loadLlmKeywordRows(String period) {
        try {
            String sql = """
                    SELECT DISTINCT ON (peer_id)
                        peer_id,
                        output_payload::text AS output_payload,
                        confidence
                    FROM peer_llm_analysis_snapshots
                    WHERE analysis_type = 'peer_overview_keywords'
                      AND scope = 'company'
                      AND comparison_mode = 'quarterly_keyword_selection'
                      AND status = 'active'
                      AND peer_id IN ('sk_ax', 'samsung_sds', 'lg_cns', 'hyundai_autoever', 'posco_dx')
                      AND (expires_at IS NULL OR expires_at > NOW())
                    ORDER BY
                        peer_id,
                        updated_at DESC NULLS LAST,
                        generated_at DESC,
                        created_at DESC
                    """;

            return jdbcTemplate.query(
                    sql,
                    rs -> {
                        Map<String, SupplementalRow> rows = new HashMap<>();
                        while (rs.next()) {
                            Map<String, Object> payload = readJsonObject(rs.getString("output_payload"));
                            String peerId = rs.getString("peer_id");
                            SupplementalRow row = mapLlmKeywordRow(payload, rs.getObject("confidence"));
                            if (row != null) {
                                rows.put(peerId, row);
                            }
                        }
                        return rows;
                    }
            );
        } catch (DataAccessException ex) {
            log.warn("PeerOverviewTable | LLM keyword snapshots unavailable, fallback rows only", ex);
            return Map.of();
        }
    }

    private SupplementalRow mapLlmKeywordRow(Map<String, Object> payload, Object confidenceValue) {
        if (payload.isEmpty()) {
            return null;
        }

        Map<String, Object> businessKeyword = objectMap(payload.get("business_keyword"));
        Map<String, Object> technologyKeyword = objectMap(payload.get("technology_keyword"));
        String businessLabel = stringValue(businessKeyword.get("label"));
        String technologyLabel = stringValue(technologyKeyword.get("label"));
        String topKeyword = stringValue(payload.get("top_keyword"));
        if (topKeyword.isBlank()) {
            topKeyword = String.join("\n", List.of(businessLabel, technologyLabel).stream()
                    .filter(value -> value != null && !value.isBlank())
                    .toList());
        }

        Double score = nullableDouble(confidenceValue);
        if (score == null) {
            Double businessConfidence = nullableDouble(businessKeyword.get("confidence"));
            Double technologyConfidence = nullableDouble(technologyKeyword.get("confidence"));
            if (businessConfidence != null && technologyConfidence != null) {
                score = (businessConfidence + technologyConfidence) / 2.0;
            } else if (businessConfidence != null) {
                score = businessConfidence;
            } else {
                score = technologyConfidence;
            }
        }

        return new SupplementalRow(
                stringValue(payload.get("peer_name")),
                null,
                topKeyword.isBlank() ? null : topKeyword,
                businessLabel.isBlank() ? null : businessLabel,
                technologyLabel.isBlank() ? null : technologyLabel,
                blankToNull(publicEvidenceText(stringValue(payload.get("top_keyword_reason")))),
                blankToNull(publicEvidenceText(stringValue(payload.get("top_keyword_basis")))),
                score,
                buildLlmKeywordEvidenceLines(payload, businessKeyword, technologyKeyword),
                stringList(payload.get("top_keyword_evidence_urls"))
        );
    }

    private List<String> buildLlmKeywordEvidenceLines(
            Map<String, Object> payload,
            Map<String, Object> businessKeyword,
            Map<String, Object> technologyKeyword
    ) {
        List<String> storedLines = stringList(payload.get("top_keyword_evidence"));
        if (!storedLines.isEmpty() && storedLines.stream().noneMatch(this::containsInternalEvidenceMarker)) {
            List<String> publicLines = storedLines.stream()
                    .map(this::publicEvidenceText)
                    .filter(value -> !value.isBlank())
                    .toList();
            if (!publicLines.isEmpty()) {
                return publicLines;
            }
        }

        List<String> generated = new ArrayList<>();
        addLlmKeywordEvidenceLine(generated, payload, businessKeyword, "사업");
        addLlmKeywordEvidenceLine(generated, payload, technologyKeyword, "기술");
        return generated;
    }

    private void addLlmKeywordEvidenceLine(
            List<String> lines,
            Map<String, Object> payload,
            Map<String, Object> keyword,
            String axisLabel
    ) {
        String label = stringValue(keyword.get("label"));
        if (label.isBlank()) {
            return;
        }
        String peerName = stringValue(payload.get("peer_name"));
        String evidenceSummary = firstNonBlank(
                stringValue(keyword.get("evidence_summary")),
                stringValue(keyword.get("reason"))
        );
        String reason = firstNonBlank(
                stringValue(keyword.get("reasoning")),
                stringValue(keyword.get("reason")),
                stringValue(payload.get("top_keyword_reason"))
        );
        lines.add(
                "%s %s 키워드 기준: %s. 근거 내용: %s. 판단 이유: %s"
                        .formatted(
                                peerName.isBlank() ? "해당 기업" : peerName,
                                axisLabel,
                                label,
                                evidenceSummary.isBlank() ? "저장된 근거 요약 없음" : publicEvidenceText(evidenceSummary),
                                reason.isBlank()
                                        ? "이 원문 내용을 보아 해당 기업의 진행 방향을 보여주는 대표 키워드로 선정했습니다."
                                        : publicEvidenceText(reason)
                        )
        );
    }

    private boolean containsInternalEvidenceMarker(String value) {
        return value != null && INTERNAL_EVIDENCE_MARKER.matcher(value).find();
    }

    private String publicEvidenceText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String cleaned = INTERNAL_EVIDENCE_MARKER.matcher(value).replaceAll("공개 근거");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        cleaned = cleaned.replaceAll("(공개 근거[와과, ]*){2,}", "공개 근거 ");
        return cleaned.replaceAll("^[,;\\s]+|[,;\\s]+$", "");
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

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            String textValue = stringValue(item);
            if (!textValue.isBlank()) {
                result.add(textValue);
            }
        }
        return result;
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
            Instant cachedAt,
            Instant dataVersion
    ) {
        private CachedPeerOverviewTable {
            if (payload == null) {
                payload = Map.of();
            }
            if (cachedAt == null) {
                cachedAt = Instant.EPOCH;
            }
            if (dataVersion == null) {
                dataVersion = Instant.EPOCH;
            }
        }
    }

    private record PeerLlmAnalysisSnapshot(
            String peerId,
            Map<String, Object> outputPayload,
            List<Map<String, Object>> analysisTrace
    ) {
    }

    private Map<String, Object> mapOf(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            map.put((String) entries[i], entries[i + 1]);
        }
        return map;
    }
}
