package com.skala.axis.query;

public final class GlobalTrendsQueries {
    public static final String COUNT_BY_DATE_RANGE = """
            SELECT COUNT(*)::int
            FROM global_industry_trends
            WHERE trend_date >= ? AND trend_date <= ?
            """;

    public static final String LIST_BY_DATE_RANGE = """
            SELECT
                id::text AS id,
                source_analysis_id,
                trend_date::text AS trend_date,
                industry,
                region,
                keyword,
                keyword_category,
                title,
                summary,
                mention_count,
                impact_score::double precision AS impact_score,
                confidence::double precision AS confidence,
                array_to_json(related_peer_ids)::text AS peer_ids_json,
                array_to_json(related_card_ids)::text AS card_ids_json,
                sk_ax_implication,
                payload::text AS payload_json,
                created_at::text AS created_at,
                updated_at::text AS updated_at
            FROM global_industry_trends
            WHERE trend_date >= ? AND trend_date <= ?
            ORDER BY trend_date DESC, impact_score DESC NULLS LAST, created_at DESC
            LIMIT ? OFFSET ?
            """;

    public static final String LATEST_TREND_DATE = """
            SELECT trend_date::text
            FROM global_industry_trends
            ORDER BY trend_date DESC, created_at DESC
            LIMIT 1
            """;

    private GlobalTrendsQueries() {
    }
}
