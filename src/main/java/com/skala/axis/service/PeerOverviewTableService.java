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
                "financialSourceLabel", "raw_article_financial_metrics 기준 (SK AX는 IR 우선, 타사는 DART 우선, 영업이익률은 DB 원값 우선)",
                "supplementalSourceLabel", "peer_companies 보조값 사용, 없으면 -",
                "rows", rows
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
                                CASE
                                    WHEN peer_id = 'sk_ax' AND source_type = 'ir' THEN 1
                                    WHEN peer_id = 'sk_ax' AND source_type = 'securities_report' THEN 2
                                    WHEN source_type = 'dart' THEN 1
                                    WHEN source_type = 'ir' THEN 2
                                    WHEN source_type = 'securities_report' THEN 3
                                    ELSE 99
                                END,
                                confidence DESC NULLS LAST,
                                updated_at DESC NULLS LAST,
                                id DESC
                        ) AS row_rank
                    FROM raw_article_financial_metrics
                    WHERE metric_scope = 'company_total'
                      AND source_type IN ('dart', 'ir', 'securities_report')
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
                                CASE
                                    WHEN metric_name IN ('operating_margin') OR metric_label = '영업이익률' THEN
                                        CASE
                                            WHEN peer_id = 'sk_ax' AND source_type = 'ir' THEN 1
                                            WHEN source_type = 'dart' THEN 1
                                            WHEN source_type = 'ir' THEN 2
                                            WHEN source_type = 'securities_report' THEN 3
                                            ELSE 99
                                        END
                                    ELSE
                                        CASE
                                            WHEN peer_id = 'sk_ax' AND source_type = 'ir' THEN 1
                                            WHEN peer_id = 'sk_ax' AND source_type = 'securities_report' THEN 2
                                            WHEN source_type = 'dart' THEN 1
                                            WHEN source_type = 'ir' THEN 2
                                            WHEN source_type = 'securities_report' THEN 3
                                            ELSE 99
                                        END
                                END,
                                confidence DESC NULLS LAST,
                                updated_at DESC NULLS LAST,
                                id DESC
                        ) AS row_rank
                    FROM raw_article_financial_metrics
                    WHERE metric_scope = 'company_total'
                      AND source_type IN ('dart', 'ir', 'securities_report')
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
                        peer_id,
                        period,
                        revenue_total_krwbn,
                        operating_profit_krwbn,
                        operating_margin_pct,
                        raw_article_id,
                        LAG(revenue_total_krwbn) OVER (
                            PARTITION BY peer_id
                            ORDER BY
                                COALESCE(NULLIF(SUBSTRING(period FROM '^([0-9]{4})'), '')::int, 0),
                                COALESCE(NULLIF(SUBSTRING(period FROM 'Q([1-4])$'), '')::int, 0)
                        ) AS prev_revenue_total_krwbn,
                        LAG(operating_profit_krwbn) OVER (
                            PARTITION BY peer_id
                            ORDER BY
                                COALESCE(NULLIF(SUBSTRING(period FROM '^([0-9]{4})'), '')::int, 0),
                                COALESCE(NULLIF(SUBSTRING(period FROM 'Q([1-4])$'), '')::int, 0)
                        ) AS prev_operating_profit_krwbn,
                        LAG(operating_margin_pct) OVER (
                            PARTITION BY peer_id
                            ORDER BY
                                COALESCE(NULLIF(SUBSTRING(period FROM '^([0-9]{4})'), '')::int, 0),
                                COALESCE(NULLIF(SUBSTRING(period FROM 'Q([1-4])$'), '')::int, 0)
                        ) AS prev_operating_margin_pct
                    FROM normalized_periods
                )
                SELECT
                    peer_id AS id,
                    revenue_total_krwbn,
                    CASE
                        WHEN prev_revenue_total_krwbn IS NOT NULL
                         AND prev_revenue_total_krwbn <> 0
                         AND revenue_total_krwbn IS NOT NULL
                        THEN ROUND(((revenue_total_krwbn - prev_revenue_total_krwbn) / prev_revenue_total_krwbn * 100)::numeric, 2)
                        ELSE NULL
                    END AS revenue_qoq_pct,
                    operating_profit_krwbn,
                    CASE
                        WHEN prev_operating_profit_krwbn IS NOT NULL
                         AND prev_operating_profit_krwbn <> 0
                         AND operating_profit_krwbn IS NOT NULL
                        THEN ROUND(((operating_profit_krwbn - prev_operating_profit_krwbn) / prev_operating_profit_krwbn * 100)::numeric, 2)
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
