package com.skala.axis.query;

public final class DashboardKeywordTrendQueries {
    public static final String KEYWORD_TREND_SQL = """
            WITH trend_rows AS (
                SELECT
                    ra.id,
                    ra.collected_at,
                    COALESCE(ra.metadata ->> 'group_name', ra.company::jsonb ->> 0) AS group_name,
                    COALESCE((ra.metadata ->> 'period')::date, ra.published_at::date) AS period,
                    NULLIF(ra.metadata ->> 'ratio', '')::numeric AS ratio,
                    ra.metadata -> 'cause_analysis' AS cause_analysis,
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
                    cause_analysis,
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
                    d.cause_analysis,
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
                cause_analysis::text AS cause_analysis,
                source_name
            FROM windowed
            ORDER BY period ASC, group_name ASC
            """;

    public static final String KEYWORD_CAUSE_SQL = """
            SELECT
                id,
                source_name,
                title,
                url,
                published_at,
                LEFT(COALESCE(content, ''), 240) AS snippet
            FROM raw_articles
            WHERE source_type <> 'search_trend'
              AND COALESCE(published_at::date, collected_at::date) BETWEEN :startDate AND :endDate
              AND url IS NOT NULL
              AND (
                  title ILIKE :keywordPattern
                  OR content ILIKE :keywordPattern
              )
            ORDER BY COALESCE(published_at, collected_at) DESC NULLS LAST, id DESC
            LIMIT 3
            """;

    public static final String INTEGRATED_ISSUE_SOURCE_ARTICLE_SQL = """
            SELECT
                COALESCE(ra.url, iisa.url) AS url,
                COALESCE(ra.source_name, iisa.source_name, iisa.publisher) AS source_name,
                COALESCE(ra.title, iisa.title) AS title,
                COALESCE(ra.published_at, iisa.published_at) AS published_at
            FROM integrated_issue_source_articles iisa
            LEFT JOIN raw_articles ra ON ra.id = iisa.raw_article_id
            WHERE iisa.integrated_issue_id = CAST(:issueId AS uuid)
              AND COALESCE(ra.url, iisa.url) IS NOT NULL
            ORDER BY
                iisa.is_analyzed_basis DESC,
                iisa.source_order ASC,
                COALESCE(ra.published_at, iisa.published_at) DESC NULLS LAST
            LIMIT 1
            """;

    public static final String INTEGRATED_ISSUE_REPRESENTATIVE_SQL = """
            SELECT
                ra.url,
                ra.source_name,
                ra.title,
                ra.published_at
            FROM integrated_issues ii
            JOIN raw_articles ra ON ra.id = ii.representative_raw_article_id
            WHERE ii.id = CAST(:issueId AS uuid)
              AND ra.url IS NOT NULL
            LIMIT 1
            """;

    public static final String CARD_NEWS_SOURCE_SQL = """
            SELECT
                ra.url,
                ra.source_name,
                ra.title,
                ra.published_at
            FROM card_news cn
            JOIN raw_articles ra
              ON ra.id = cn.primary_raw_article_id
              OR ra.id = ANY(COALESCE(cn.source_raw_article_ids, '{}'::bigint[]))
            WHERE cn.id = :cardNewsId
              AND ra.url IS NOT NULL
            ORDER BY
                CASE WHEN ra.id = cn.primary_raw_article_id THEN 0 ELSE 1 END,
                ra.published_at DESC NULLS LAST
            LIMIT 1
            """;

    public static final String BUSINESS_SIGNAL_SOURCE_BY_RAW_ARTICLE_SQL = """
            SELECT
                url,
                source_name,
                title,
                published_at
            FROM raw_articles
            WHERE id::text = :rawArticleId
              AND url IS NOT NULL
            LIMIT 1
            """;

    public static final String BUSINESS_SIGNAL_SOURCE_SQL = """
            SELECT
                ra.url,
                ra.source_name,
                ra.title,
                ra.published_at
            FROM raw_article_business_signals rabs
            JOIN raw_articles ra ON ra.id = rabs.raw_article_id
            WHERE rabs.id::text = :signalId
              AND ra.url IS NOT NULL
            LIMIT 1
            """;

    private DashboardKeywordTrendQueries() {
    }
}
