package com.skala.axis.query;

public final class PeerOverviewFinancialQueries {
    public static final String RESOLVE_RAW_FINANCIAL_COMMON_PERIOD_SQL = """
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

    public static final String RAW_FINANCIAL_ROWS_SQL = """
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

    public static final String RESOLVE_PEER_FINANCIALS_COMMON_PERIOD_SQL = """
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

    public static final String PEER_FINANCIAL_ROWS_SQL = """
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

    private PeerOverviewFinancialQueries() {
    }
}
