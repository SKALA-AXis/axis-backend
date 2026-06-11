package com.skala.axis.query;

public final class RawArticleQueries {
    public static final String COUNT_BY_FILTER = "SELECT COUNT(*) FROM raw_articles %s";

    public static final String LIST_BY_FILTER = """
            SELECT
                id,
                title,
                url,
                source_type,
                source_name,
                publisher,
                company::text AS company_json,
                published_at,
                collected_at,
                importance_level,
                importance_score,
                processing_status,
                relevance_label
            FROM raw_articles
            %s
            ORDER BY collected_at DESC NULLS LAST, id DESC
            LIMIT ? OFFSET ?
            """;

    public static final String DETAIL_BY_ID = """
            SELECT
                ra.id,
                ra.title,
                ra.content,
                ra.url,
                ra.source_type,
                ra.source_name,
                ra.publisher,
                ra.company::text AS company_json,
                ra.published_at,
                ra.collected_at,
                ra.importance_level,
                ra.importance_score,
                ra.processing_status,
                ra.relevance_label,
                ra.relevance_reason,
                COALESCE(mu.metadata, '{}'::jsonb)::text AS metadata_json
            FROM raw_articles ra
            LEFT JOIN raw_article_metadata_unified mu
                ON mu.raw_article_id = ra.id
            WHERE ra.id = ?
            LIMIT 1
            """;

    private RawArticleQueries() {
    }
}
