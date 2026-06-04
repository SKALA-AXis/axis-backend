package com.skala.axis.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    public Map<String, Object> getPeerOverviewTable() {
        String period = resolveCommonPeriod();
        Map<String, SupplementalRow> supplementalRows = loadSupplementalRows();
        List<Map<String, Object>> rows = loadRows(period, supplementalRows);
        return mapOf(
                "periodLabel", period,
                "coverageLabel", period == null ? "공통 분기 미확보" : "SK AX · 삼성 SDS · LG CNS · 현대 오토에버 · 포스코 DX 공통 분기 기준",
                "financialSourceLabel", "raw_article_financial_metrics IR 기준 (영업이익률은 IR 원값 우선, 없으면 매출·영업이익으로 계산)",
                "supplementalSourceLabel", "peer_companies 보조값 사용, 없으면 -",
                "rows", rows
        );
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
                "financialSourceLabel", "raw_article_financial_metrics IR 기준 (company_total / revenue_total / revenue_total_yoy, YoY 없으면 전년 동분기 매출로 계산)",
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
                            ELSE COALESCE(NULLIF(metric_label, ''), metric_name)
                        END AS metric_name_canonical,
                        COALESCE(value_krwbn, value_numeric::double precision) AS metric_value,
                        ROW_NUMBER() OVER (
                            PARTITION BY peer_id, period,
                                CASE
                                    WHEN COALESCE(NULLIF(metric_label, ''), metric_name) IN ('매출', '매출액', '총매출', 'revenue_total', 'revenue') THEN 'revenue_total'
                                    WHEN COALESCE(NULLIF(metric_label, ''), metric_name) IN ('영업이익', 'operating_profit', 'operating_income') THEN 'operating_profit'
                                    WHEN COALESCE(NULLIF(metric_label, ''), metric_name) IN ('영업이익률', 'operating_margin') THEN 'operating_margin'
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
            baseRow.put("operatingMarginPct", null);
            baseRow.put("operatingMarginQoqDeltaPctp", null);
            baseRow.put("axRevenueSharePct", null);
            baseRow.put("contractCount", null);
            baseRow.put("topKeyword", null);
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
                baseRow.put("contractCount", supplementalRow.contractCount());
                baseRow.put("topKeyword", supplementalRow.topKeyword());
            }

            rows.add(baseRow);
        }
        return rows;
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
                        MAX(CASE WHEN metric_name_canonical = 'operating_margin'
                            THEN COALESCE(value_numeric::double precision, value_krwbn::double precision)
                        END) AS operating_margin_pct,
                        MAX(raw_article_id) FILTER (WHERE metric_name_canonical IN ('revenue_total', 'operating_profit', 'operating_margin')) AS raw_article_id
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
                        current_period.operating_margin_pct,
                        current_period.raw_article_id,
                        previous_period.revenue_total_krwbn AS prev_revenue_total_krwbn,
                        previous_period.operating_profit_krwbn AS prev_operating_profit_krwbn,
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
                    SELECT
                        id,
                        name,
                        ax_revenue_share_pct,
                        contract_count,
                        COALESCE(core_keywords[1], keywords[1]) AS top_keyword
                    FROM peer_companies
                    WHERE id IN ('sk_ax', 'samsung_sds', 'lg_cns', 'hyundai_autoever', 'posco_dx')
                    """;

            return jdbcTemplate.query(sql, rs -> {
                Map<String, SupplementalRow> rows = new HashMap<>();
                while (rs.next()) {
                    rows.put(
                            rs.getString("id"),
                            new SupplementalRow(
                                    rs.getString("name"),
                                    nullableDouble(rs.getObject("ax_revenue_share_pct")),
                                    rs.getObject("contract_count") == null ? null : rs.getInt("contract_count"),
                                    rs.getString("top_keyword")
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

    private record DisplayPeer(int displayOrder, String id, String label) {
    }

    private record SupplementalRow(
            String label,
            Double axRevenueSharePct,
            Integer contractCount,
            String topKeyword
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
