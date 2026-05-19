-- V25: keep source-specific metadata tables, remove redundant projections.
--
-- The source payloads are already stored in raw_article_metadata_* tables.
-- Keep raw_article_parse_results as the lightweight parser summary, but stop
-- duplicating sections/chunks/tables/media/keywords/market points into separate
-- projection tables until a concrete reader needs them.

CREATE OR REPLACE FUNCTION axis_refresh_raw_article_normalized_metadata(
    target_raw_article_id BIGINT
)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    meta JSONB;
    article_source_type TEXT;
    parser_result JSONB;
BEGIN
    SELECT source_type, source_metadata
    INTO article_source_type, meta
    FROM raw_article_metadata_unified
    WHERE raw_article_id = target_raw_article_id;

    IF NOT FOUND THEN
        RETURN;
    END IF;

    meta := COALESCE(meta, '{}'::jsonb);
    parser_result := CASE
        WHEN jsonb_typeof(meta -> 'parser_result') = 'object'
            THEN meta -> 'parser_result'
        ELSE NULL
    END;

    DELETE FROM raw_article_parse_results
    WHERE raw_article_id = target_raw_article_id;

    IF parser_result IS NOT NULL THEN
        INSERT INTO raw_article_parse_results (
            raw_article_id,
            source_type,
            parser,
            parser_ok,
            period,
            period_year,
            period_quarter,
            period_type,
            published_at,
            parser_quality_score,
            parser_quality_label,
            parser_quality_reason,
            financial_record,
            result_metadata,
            warnings,
            raw_result
        )
        VALUES (
            target_raw_article_id,
            article_source_type,
            COALESCE(parser_result ->> 'parser', parser_result ->> 'source'),
            CASE
                WHEN parser_result ? 'ok' THEN (parser_result ->> 'ok')::BOOLEAN
                ELSE NULL
            END,
            COALESCE(parser_result ->> 'period', meta ->> 'period'),
            axis_to_int(COALESCE(parser_result ->> 'period_year', meta ->> 'period_year')),
            axis_to_int(COALESCE(parser_result ->> 'period_quarter', meta ->> 'period_quarter')),
            COALESCE(parser_result ->> 'period_type', meta ->> 'period_type'),
            COALESCE(parser_result ->> 'published_at', meta ->> 'published_at'),
            axis_to_numeric(meta ->> 'parser_quality_score')::DOUBLE PRECISION,
            COALESCE(meta ->> 'parser_quality_label', parser_result ->> 'parser_quality_label'),
            COALESCE(meta ->> 'parser_quality_reason', parser_result ->> 'parser_quality_reason'),
            COALESCE(parser_result -> 'financial_record', meta -> 'financial_record', '{}'::jsonb),
            COALESCE(parser_result -> 'metadata', '{}'::jsonb),
            CASE
                WHEN jsonb_typeof(parser_result -> 'warnings') = 'array'
                    THEN parser_result -> 'warnings'
                ELSE '[]'::jsonb
            END,
            parser_result
        )
        ON CONFLICT (raw_article_id) DO UPDATE SET
            source_type = EXCLUDED.source_type,
            parser = EXCLUDED.parser,
            parser_ok = EXCLUDED.parser_ok,
            period = EXCLUDED.period,
            period_year = EXCLUDED.period_year,
            period_quarter = EXCLUDED.period_quarter,
            period_type = EXCLUDED.period_type,
            published_at = EXCLUDED.published_at,
            parser_quality_score = EXCLUDED.parser_quality_score,
            parser_quality_label = EXCLUDED.parser_quality_label,
            parser_quality_reason = EXCLUDED.parser_quality_reason,
            financial_record = EXCLUDED.financial_record,
            result_metadata = EXCLUDED.result_metadata,
            warnings = EXCLUDED.warnings,
            raw_result = EXCLUDED.raw_result,
            updated_at = NOW();
    END IF;
END;
$$;

DROP TABLE IF EXISTS raw_article_document_sections;
DROP TABLE IF EXISTS raw_article_document_chunks;
DROP TABLE IF EXISTS raw_article_document_tables;
DROP TABLE IF EXISTS raw_article_media_assets;
DROP TABLE IF EXISTS raw_article_search_trend_keywords;
DROP TABLE IF EXISTS raw_article_market_data_points;
