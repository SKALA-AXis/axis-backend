package com.skala.axis.query;

public final class PeerOverviewPositioningQueries {
    public static final String RESOLVE_COMMON_PERIOD_SQL = """
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

    private static final String POSITIONING_SOURCE_CTES = """
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
            """;

    public static final String POSITIONING_POINTS_SQL = POSITIONING_SOURCE_CTES + """
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

    public static final String LATEST_POSITIONING_POINTS_SQL = POSITIONING_SOURCE_CTES + """
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

    private PeerOverviewPositioningQueries() {
    }
}
