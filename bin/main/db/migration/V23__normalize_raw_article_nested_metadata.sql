-- V23: normalize repeated/nested raw article metadata structures.
--
-- V22 split source payloads out of raw_articles.metadata. This migration
-- keeps the source_metadata JSONB payloads for compatibility, but also
-- projects repeated structures into relational tables that can be joined,
-- indexed, counted, and validated without reparsing large JSON blobs.

CREATE OR REPLACE FUNCTION axis_to_int(value TEXT)
RETURNS INT
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT CASE
        WHEN value ~ '^-?[0-9]+$' THEN value::INT
        ELSE NULL
    END;
$$;

CREATE OR REPLACE FUNCTION axis_to_bigint(value TEXT)
RETURNS BIGINT
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT CASE
        WHEN value ~ '^-?[0-9]+$' THEN value::BIGINT
        ELSE NULL
    END;
$$;

CREATE OR REPLACE FUNCTION axis_to_numeric(value TEXT)
RETURNS NUMERIC
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT CASE
        WHEN value ~ '^-?[0-9]+(\.[0-9]+)?$' THEN value::NUMERIC
        ELSE NULL
    END;
$$;

CREATE OR REPLACE FUNCTION axis_to_date(value TEXT)
RETURNS DATE
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT CASE
        WHEN value ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' THEN value::DATE
        ELSE NULL
    END;
$$;

CREATE TABLE IF NOT EXISTS raw_article_parse_results (
    raw_article_id BIGINT PRIMARY KEY,
    source_type VARCHAR(50) NOT NULL,
    parser TEXT,
    parser_ok BOOLEAN,
    period TEXT,
    period_year INT,
    period_quarter INT,
    period_type TEXT,
    published_at TEXT,
    parser_quality_score DOUBLE PRECISION,
    parser_quality_label TEXT,
    parser_quality_reason TEXT,
    financial_record JSONB NOT NULL DEFAULT '{}',
    result_metadata JSONB NOT NULL DEFAULT '{}',
    warnings JSONB NOT NULL DEFAULT '[]',
    raw_result JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_raw_article_parse_results_article
        FOREIGN KEY (raw_article_id) REFERENCES raw_articles(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS raw_article_document_sections (
    id BIGSERIAL PRIMARY KEY,
    raw_article_id BIGINT NOT NULL,
    section_uid TEXT NOT NULL,
    section_source TEXT NOT NULL,
    section_key TEXT,
    section_order INT,
    section_title TEXT,
    pages JSONB NOT NULL DEFAULT '[]',
    topics JSONB NOT NULL DEFAULT '[]',
    signals JSONB NOT NULL DEFAULT '[]',
    text_chars INT,
    chunk_count INT,
    snippet TEXT,
    payload JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_raw_article_document_sections_article
        FOREIGN KEY (raw_article_id) REFERENCES raw_articles(id) ON DELETE CASCADE,
    CONSTRAINT uq_raw_article_document_section
        UNIQUE (raw_article_id, section_uid)
);

CREATE TABLE IF NOT EXISTS raw_article_document_chunks (
    id BIGSERIAL PRIMARY KEY,
    raw_article_id BIGINT NOT NULL,
    chunk_uid TEXT NOT NULL,
    chunk_source TEXT NOT NULL,
    chunk_id TEXT,
    section_key TEXT,
    section_title TEXT,
    chunk_index INT,
    page INT,
    text_chars INT,
    text TEXT,
    topics JSONB NOT NULL DEFAULT '[]',
    topic_signals JSONB NOT NULL DEFAULT '{}',
    payload JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_raw_article_document_chunks_article
        FOREIGN KEY (raw_article_id) REFERENCES raw_articles(id) ON DELETE CASCADE,
    CONSTRAINT uq_raw_article_document_chunk
        UNIQUE (raw_article_id, chunk_uid)
);

CREATE TABLE IF NOT EXISTS raw_article_document_tables (
    id BIGSERIAL PRIMARY KEY,
    raw_article_id BIGINT NOT NULL,
    table_uid TEXT NOT NULL,
    table_source TEXT NOT NULL,
    table_index INT,
    page INT,
    filename TEXT,
    title TEXT,
    row_count INT,
    column_count INT,
    text TEXT,
    payload JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_raw_article_document_tables_article
        FOREIGN KEY (raw_article_id) REFERENCES raw_articles(id) ON DELETE CASCADE,
    CONSTRAINT uq_raw_article_document_table
        UNIQUE (raw_article_id, table_uid)
);

CREATE TABLE IF NOT EXISTS raw_article_media_assets (
    id BIGSERIAL PRIMARY KEY,
    raw_article_id BIGINT NOT NULL,
    asset_uid TEXT NOT NULL,
    asset_type VARCHAR(40) NOT NULL,
    source_field TEXT NOT NULL,
    asset_order INT,
    url TEXT,
    local_path TEXT,
    caption TEXT,
    page INT,
    payload JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_raw_article_media_assets_article
        FOREIGN KEY (raw_article_id) REFERENCES raw_articles(id) ON DELETE CASCADE,
    CONSTRAINT uq_raw_article_media_asset
        UNIQUE (raw_article_id, asset_uid)
);

CREATE TABLE IF NOT EXISTS raw_article_search_trend_keywords (
    id BIGSERIAL PRIMARY KEY,
    raw_article_id BIGINT NOT NULL,
    keyword_order INT NOT NULL,
    keyword TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_raw_article_search_trend_keywords_article
        FOREIGN KEY (raw_article_id) REFERENCES raw_articles(id) ON DELETE CASCADE,
    CONSTRAINT uq_raw_article_search_trend_keyword_order
        UNIQUE (raw_article_id, keyword_order)
);

CREATE TABLE IF NOT EXISTS raw_article_market_data_points (
    id BIGSERIAL PRIMARY KEY,
    raw_article_id BIGINT NOT NULL,
    point_order INT NOT NULL,
    point_date DATE,
    open NUMERIC,
    high NUMERIC,
    low NUMERIC,
    close NUMERIC,
    volume BIGINT,
    change_pct NUMERIC,
    payload JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_raw_article_market_data_points_article
        FOREIGN KEY (raw_article_id) REFERENCES raw_articles(id) ON DELETE CASCADE,
    CONSTRAINT uq_raw_article_market_data_point_order
        UNIQUE (raw_article_id, point_order)
);

CREATE INDEX IF NOT EXISTS idx_raw_article_parse_results_source_period
    ON raw_article_parse_results (source_type, period);
CREATE INDEX IF NOT EXISTS idx_raw_article_parse_results_quality
    ON raw_article_parse_results (parser_quality_label);

CREATE INDEX IF NOT EXISTS idx_raw_article_document_sections_article
    ON raw_article_document_sections (raw_article_id);
CREATE INDEX IF NOT EXISTS idx_raw_article_document_sections_key
    ON raw_article_document_sections (section_key);
CREATE INDEX IF NOT EXISTS idx_raw_article_document_sections_topics
    ON raw_article_document_sections USING GIN(topics);

CREATE INDEX IF NOT EXISTS idx_raw_article_document_chunks_article
    ON raw_article_document_chunks (raw_article_id);
CREATE INDEX IF NOT EXISTS idx_raw_article_document_chunks_section
    ON raw_article_document_chunks (section_key);

CREATE INDEX IF NOT EXISTS idx_raw_article_document_tables_article
    ON raw_article_document_tables (raw_article_id);
CREATE INDEX IF NOT EXISTS idx_raw_article_document_tables_page
    ON raw_article_document_tables (page);

CREATE INDEX IF NOT EXISTS idx_raw_article_media_assets_article
    ON raw_article_media_assets (raw_article_id);
CREATE INDEX IF NOT EXISTS idx_raw_article_media_assets_type
    ON raw_article_media_assets (asset_type);
CREATE INDEX IF NOT EXISTS idx_raw_article_media_assets_url
    ON raw_article_media_assets (url);

CREATE INDEX IF NOT EXISTS idx_raw_article_search_trend_keywords_keyword
    ON raw_article_search_trend_keywords (keyword);

CREATE INDEX IF NOT EXISTS idx_raw_article_market_data_points_date
    ON raw_article_market_data_points (point_date);

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
    DELETE FROM raw_article_document_sections
    WHERE raw_article_id = target_raw_article_id;
    DELETE FROM raw_article_document_chunks
    WHERE raw_article_id = target_raw_article_id;
    DELETE FROM raw_article_document_tables
    WHERE raw_article_id = target_raw_article_id;
    DELETE FROM raw_article_media_assets
    WHERE raw_article_id = target_raw_article_id;
    DELETE FROM raw_article_search_trend_keywords
    WHERE raw_article_id = target_raw_article_id;
    DELETE FROM raw_article_market_data_points
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
            meta ->> 'parser_quality_label',
            meta ->> 'parser_quality_reason',
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

    IF jsonb_typeof(parser_result -> 'sections') = 'array' THEN
        INSERT INTO raw_article_document_sections (
            raw_article_id,
            section_uid,
            section_source,
            section_key,
            section_order,
            section_title,
            pages,
            topics,
            signals,
            text_chars,
            chunk_count,
            snippet,
            payload
        )
        SELECT
            target_raw_article_id,
            'parser_result:' || ordinality::TEXT || ':'
                || COALESCE(section_value ->> 'section_key', section_value ->> 'id', ''),
            'parser_result.sections',
            section_value ->> 'section_key',
            COALESCE(axis_to_int(section_value ->> 'section_order'), ordinality::INT),
            section_value ->> 'section_title',
            CASE
                WHEN jsonb_typeof(section_value -> 'pages') = 'array'
                    THEN section_value -> 'pages'
                ELSE '[]'::jsonb
            END,
            CASE
                WHEN jsonb_typeof(section_value -> 'topics') = 'array'
                    THEN section_value -> 'topics'
                ELSE '[]'::jsonb
            END,
            CASE
                WHEN jsonb_typeof(section_value -> 'signals') = 'array'
                    THEN section_value -> 'signals'
                ELSE '[]'::jsonb
            END,
            axis_to_int(section_value ->> 'text_chars'),
            axis_to_int(section_value ->> 'chunk_count'),
            section_value ->> 'snippet',
            section_value
        FROM jsonb_array_elements(parser_result -> 'sections')
            WITH ORDINALITY AS section_item(section_value, ordinality)
        WHERE jsonb_typeof(section_value) = 'object'
        ON CONFLICT (raw_article_id, section_uid) DO UPDATE SET
            payload = EXCLUDED.payload;
    END IF;

    IF jsonb_typeof(parser_result -> 'sections') = 'object' THEN
        INSERT INTO raw_article_document_sections (
            raw_article_id,
            section_uid,
            section_source,
            section_key,
            section_order,
            section_title,
            pages,
            topics,
            signals,
            text_chars,
            chunk_count,
            snippet,
            payload
        )
        SELECT
            target_raw_article_id,
            'parser_result:' || section_key,
            'parser_result.sections',
            section_key,
            ordinality::INT,
            COALESCE(section_value ->> 'section_title', section_value ->> 'title', section_key),
            CASE
                WHEN jsonb_typeof(section_value -> 'pages') = 'array'
                    THEN section_value -> 'pages'
                ELSE '[]'::jsonb
            END,
            CASE
                WHEN jsonb_typeof(section_value -> 'topics') = 'array'
                    THEN section_value -> 'topics'
                ELSE '[]'::jsonb
            END,
            CASE
                WHEN jsonb_typeof(section_value -> 'signals') = 'array'
                    THEN section_value -> 'signals'
                ELSE '[]'::jsonb
            END,
            axis_to_int(section_value ->> 'text_chars'),
            axis_to_int(section_value ->> 'chunk_count'),
            section_value ->> 'snippet',
            section_value
        FROM jsonb_each(parser_result -> 'sections')
            WITH ORDINALITY AS section_item(section_key, section_value, ordinality)
        WHERE jsonb_typeof(section_value) = 'object'
        ON CONFLICT (raw_article_id, section_uid) DO UPDATE SET
            payload = EXCLUDED.payload;
    END IF;

    IF jsonb_typeof(meta -> 'dart_sections') = 'object' THEN
        INSERT INTO raw_article_document_sections (
            raw_article_id,
            section_uid,
            section_source,
            section_key,
            section_order,
            section_title,
            topics,
            payload
        )
        SELECT
            target_raw_article_id,
            'dart_sections:' || section_key,
            'source_metadata.dart_sections',
            section_key,
            ordinality::INT,
            COALESCE(section_value ->> 'section_title', section_value ->> 'title', section_key),
            CASE
                WHEN jsonb_typeof(section_value -> 'topics') = 'array'
                    THEN section_value -> 'topics'
                ELSE '[]'::jsonb
            END,
            section_value
        FROM jsonb_each(meta -> 'dart_sections')
            WITH ORDINALITY AS section_item(section_key, section_value, ordinality)
        WHERE jsonb_typeof(section_value) = 'object'
        ON CONFLICT (raw_article_id, section_uid) DO UPDATE SET
            payload = EXCLUDED.payload;
    END IF;

    IF jsonb_typeof(meta -> 'ir_sections') = 'array' THEN
        INSERT INTO raw_article_document_sections (
            raw_article_id,
            section_uid,
            section_source,
            section_key,
            section_order,
            section_title,
            pages,
            topics,
            signals,
            text_chars,
            payload
        )
        SELECT
            target_raw_article_id,
            'ir_sections:' || ordinality::TEXT || ':'
                || COALESCE(section_value ->> 'section_key', ''),
            'source_metadata.ir_sections',
            section_value ->> 'section_key',
            COALESCE(axis_to_int(section_value ->> 'section_order'), ordinality::INT),
            section_value ->> 'section_title',
            CASE
                WHEN jsonb_typeof(section_value -> 'pages') = 'array'
                    THEN section_value -> 'pages'
                ELSE '[]'::jsonb
            END,
            CASE
                WHEN jsonb_typeof(section_value -> 'topics') = 'array'
                    THEN section_value -> 'topics'
                ELSE '[]'::jsonb
            END,
            CASE
                WHEN jsonb_typeof(section_value -> 'signals') = 'array'
                    THEN section_value -> 'signals'
                ELSE '[]'::jsonb
            END,
            axis_to_int(section_value ->> 'text_chars'),
            section_value
        FROM jsonb_array_elements(meta -> 'ir_sections')
            WITH ORDINALITY AS section_item(section_value, ordinality)
        WHERE jsonb_typeof(section_value) = 'object'
        ON CONFLICT (raw_article_id, section_uid) DO UPDATE SET
            payload = EXCLUDED.payload;
    END IF;

    IF jsonb_typeof(parser_result -> 'document_chunks') = 'array' THEN
        INSERT INTO raw_article_document_chunks (
            raw_article_id,
            chunk_uid,
            chunk_source,
            chunk_id,
            section_key,
            section_title,
            chunk_index,
            page,
            text_chars,
            text,
            topics,
            topic_signals,
            payload
        )
        SELECT
            target_raw_article_id,
            'parser_result:' || ordinality::TEXT || ':'
                || COALESCE(chunk_value ->> 'chunk_id', ''),
            'parser_result.document_chunks',
            chunk_value ->> 'chunk_id',
            chunk_value ->> 'section_key',
            chunk_value ->> 'section_title',
            COALESCE(axis_to_int(chunk_value ->> 'chunk_index'), ordinality::INT),
            axis_to_int(chunk_value ->> 'page'),
            axis_to_int(chunk_value ->> 'text_chars'),
            chunk_value ->> 'text',
            CASE
                WHEN jsonb_typeof(chunk_value -> 'topics') = 'array'
                    THEN chunk_value -> 'topics'
                ELSE '[]'::jsonb
            END,
            CASE
                WHEN jsonb_typeof(chunk_value -> 'topic_signals') = 'object'
                    THEN chunk_value -> 'topic_signals'
                ELSE '{}'::jsonb
            END,
            chunk_value
        FROM jsonb_array_elements(parser_result -> 'document_chunks')
            WITH ORDINALITY AS chunk_item(chunk_value, ordinality)
        WHERE jsonb_typeof(chunk_value) = 'object'
        ON CONFLICT (raw_article_id, chunk_uid) DO UPDATE SET
            payload = EXCLUDED.payload;
    END IF;

    IF jsonb_typeof(meta -> 'dart_document_chunks') = 'array' THEN
        INSERT INTO raw_article_document_chunks (
            raw_article_id,
            chunk_uid,
            chunk_source,
            chunk_id,
            section_key,
            section_title,
            chunk_index,
            page,
            text_chars,
            text,
            topics,
            topic_signals,
            payload
        )
        SELECT
            target_raw_article_id,
            'dart_document_chunks:' || ordinality::TEXT || ':'
                || COALESCE(chunk_value ->> 'chunk_id', ''),
            'source_metadata.dart_document_chunks',
            chunk_value ->> 'chunk_id',
            chunk_value ->> 'section_key',
            chunk_value ->> 'section_title',
            COALESCE(axis_to_int(chunk_value ->> 'chunk_index'), ordinality::INT),
            axis_to_int(chunk_value ->> 'page'),
            axis_to_int(chunk_value ->> 'text_chars'),
            chunk_value ->> 'text',
            CASE
                WHEN jsonb_typeof(chunk_value -> 'topics') = 'array'
                    THEN chunk_value -> 'topics'
                ELSE '[]'::jsonb
            END,
            CASE
                WHEN jsonb_typeof(chunk_value -> 'topic_signals') = 'object'
                    THEN chunk_value -> 'topic_signals'
                ELSE '{}'::jsonb
            END,
            chunk_value
        FROM jsonb_array_elements(meta -> 'dart_document_chunks')
            WITH ORDINALITY AS chunk_item(chunk_value, ordinality)
        WHERE jsonb_typeof(chunk_value) = 'object'
        ON CONFLICT (raw_article_id, chunk_uid) DO UPDATE SET
            payload = EXCLUDED.payload;
    END IF;

    IF jsonb_typeof(meta -> 'ir_document_chunks') = 'array' THEN
        INSERT INTO raw_article_document_chunks (
            raw_article_id,
            chunk_uid,
            chunk_source,
            chunk_id,
            section_key,
            section_title,
            chunk_index,
            page,
            text_chars,
            text,
            topics,
            topic_signals,
            payload
        )
        SELECT
            target_raw_article_id,
            'ir_document_chunks:' || ordinality::TEXT || ':'
                || COALESCE(chunk_value ->> 'chunk_id', ''),
            'source_metadata.ir_document_chunks',
            chunk_value ->> 'chunk_id',
            chunk_value ->> 'section_key',
            chunk_value ->> 'section_title',
            COALESCE(axis_to_int(chunk_value ->> 'chunk_index'), ordinality::INT),
            axis_to_int(chunk_value ->> 'page'),
            axis_to_int(chunk_value ->> 'text_chars'),
            chunk_value ->> 'text',
            CASE
                WHEN jsonb_typeof(chunk_value -> 'topics') = 'array'
                    THEN chunk_value -> 'topics'
                ELSE '[]'::jsonb
            END,
            CASE
                WHEN jsonb_typeof(chunk_value -> 'topic_signals') = 'object'
                    THEN chunk_value -> 'topic_signals'
                ELSE '{}'::jsonb
            END,
            chunk_value
        FROM jsonb_array_elements(meta -> 'ir_document_chunks')
            WITH ORDINALITY AS chunk_item(chunk_value, ordinality)
        WHERE jsonb_typeof(chunk_value) = 'object'
        ON CONFLICT (raw_article_id, chunk_uid) DO UPDATE SET
            payload = EXCLUDED.payload;
    END IF;

    IF jsonb_typeof(meta -> 'tables') = 'array' THEN
        INSERT INTO raw_article_document_tables (
            raw_article_id,
            table_uid,
            table_source,
            table_index,
            page,
            filename,
            title,
            row_count,
            column_count,
            text,
            payload
        )
        SELECT
            target_raw_article_id,
            'source_metadata.tables:' || ordinality::TEXT || ':'
                || COALESCE(table_value ->> 'table_index', table_value ->> 'page', ''),
            'source_metadata.tables',
            COALESCE(axis_to_int(table_value ->> 'table_index'), ordinality::INT),
            axis_to_int(table_value ->> 'page'),
            table_value ->> 'filename',
            table_value ->> 'title',
            axis_to_int(table_value ->> 'row_count'),
            axis_to_int(table_value ->> 'column_count'),
            table_value ->> 'text',
            table_value
        FROM jsonb_array_elements(meta -> 'tables')
            WITH ORDINALITY AS table_item(table_value, ordinality)
        WHERE jsonb_typeof(table_value) = 'object'
        ON CONFLICT (raw_article_id, table_uid) DO UPDATE SET
            payload = EXCLUDED.payload;
    END IF;

    IF jsonb_typeof(parser_result #> '{document,tables}') = 'array' THEN
        INSERT INTO raw_article_document_tables (
            raw_article_id,
            table_uid,
            table_source,
            table_index,
            page,
            filename,
            title,
            row_count,
            column_count,
            text,
            payload
        )
        SELECT
            target_raw_article_id,
            'parser_result.document.tables:' || ordinality::TEXT || ':'
                || COALESCE(table_value ->> 'table_index', table_value ->> 'page', ''),
            'parser_result.document.tables',
            COALESCE(axis_to_int(table_value ->> 'table_index'), ordinality::INT),
            axis_to_int(table_value ->> 'page'),
            table_value ->> 'filename',
            table_value ->> 'title',
            axis_to_int(table_value ->> 'row_count'),
            axis_to_int(table_value ->> 'column_count'),
            table_value ->> 'text',
            table_value
        FROM jsonb_array_elements(parser_result #> '{document,tables}')
            WITH ORDINALITY AS table_item(table_value, ordinality)
        WHERE jsonb_typeof(table_value) = 'object'
        ON CONFLICT (raw_article_id, table_uid) DO UPDATE SET
            payload = EXCLUDED.payload;
    END IF;

    IF jsonb_typeof(parser_result #> '{metadata,tables}') = 'array' THEN
        INSERT INTO raw_article_document_tables (
            raw_article_id,
            table_uid,
            table_source,
            table_index,
            page,
            filename,
            title,
            row_count,
            column_count,
            text,
            payload
        )
        SELECT
            target_raw_article_id,
            'parser_result.metadata.tables:' || ordinality::TEXT || ':'
                || COALESCE(table_value ->> 'table_index', table_value ->> 'page', ''),
            'parser_result.metadata.tables',
            COALESCE(axis_to_int(table_value ->> 'table_index'), ordinality::INT),
            axis_to_int(table_value ->> 'page'),
            table_value ->> 'filename',
            table_value ->> 'title',
            axis_to_int(table_value ->> 'row_count'),
            axis_to_int(table_value ->> 'column_count'),
            table_value ->> 'text',
            table_value
        FROM jsonb_array_elements(parser_result #> '{metadata,tables}')
            WITH ORDINALITY AS table_item(table_value, ordinality)
        WHERE jsonb_typeof(table_value) = 'object'
        ON CONFLICT (raw_article_id, table_uid) DO UPDATE SET
            payload = EXCLUDED.payload;
    END IF;

    IF jsonb_typeof(meta -> 'image_urls') = 'array' THEN
        INSERT INTO raw_article_media_assets (
            raw_article_id,
            asset_uid,
            asset_type,
            source_field,
            asset_order,
            url,
            payload
        )
        SELECT
            target_raw_article_id,
            'image_urls:' || ordinality::TEXT || ':' || md5(COALESCE(asset_value #>> '{}', asset_value::TEXT)),
            'image_url',
            'image_urls',
            ordinality::INT,
            CASE
                WHEN jsonb_typeof(asset_value) = 'string' THEN asset_value #>> '{}'
                WHEN jsonb_typeof(asset_value) = 'object' THEN COALESCE(asset_value ->> 'url', asset_value ->> 'src')
                ELSE NULL
            END,
            asset_value
        FROM jsonb_array_elements(meta -> 'image_urls')
            WITH ORDINALITY AS asset_item(asset_value, ordinality)
        ON CONFLICT (raw_article_id, asset_uid) DO UPDATE SET
            payload = EXCLUDED.payload;
    END IF;

    IF jsonb_typeof(meta -> 'pdf_urls') = 'array' THEN
        INSERT INTO raw_article_media_assets (
            raw_article_id,
            asset_uid,
            asset_type,
            source_field,
            asset_order,
            url,
            payload
        )
        SELECT
            target_raw_article_id,
            'pdf_urls:' || ordinality::TEXT || ':' || md5(COALESCE(asset_value #>> '{}', asset_value::TEXT)),
            'pdf_url',
            'pdf_urls',
            ordinality::INT,
            CASE
                WHEN jsonb_typeof(asset_value) = 'string' THEN asset_value #>> '{}'
                WHEN jsonb_typeof(asset_value) = 'object' THEN COALESCE(asset_value ->> 'url', asset_value ->> 'href')
                ELSE NULL
            END,
            asset_value
        FROM jsonb_array_elements(meta -> 'pdf_urls')
            WITH ORDINALITY AS asset_item(asset_value, ordinality)
        ON CONFLICT (raw_article_id, asset_uid) DO UPDATE SET
            payload = EXCLUDED.payload;
    END IF;

    IF jsonb_typeof(meta -> 'visual_images') = 'array' THEN
        INSERT INTO raw_article_media_assets (
            raw_article_id,
            asset_uid,
            asset_type,
            source_field,
            asset_order,
            url,
            local_path,
            caption,
            page,
            payload
        )
        SELECT
            target_raw_article_id,
            'visual_images:' || ordinality::TEXT || ':' || md5(asset_value::TEXT),
            'visual_image',
            'visual_images',
            ordinality::INT,
            asset_value ->> 'url',
            asset_value ->> 'image_path',
            asset_value ->> 'caption',
            axis_to_int(asset_value ->> 'page'),
            asset_value
        FROM jsonb_array_elements(meta -> 'visual_images')
            WITH ORDINALITY AS asset_item(asset_value, ordinality)
        WHERE jsonb_typeof(asset_value) = 'object'
        ON CONFLICT (raw_article_id, asset_uid) DO UPDATE SET
            payload = EXCLUDED.payload;
    END IF;

    IF jsonb_typeof(meta -> 'keywords') = 'array' THEN
        INSERT INTO raw_article_search_trend_keywords (
            raw_article_id,
            keyword_order,
            keyword
        )
        SELECT
            target_raw_article_id,
            ordinality::INT,
            keyword_value #>> '{}'
        FROM jsonb_array_elements(meta -> 'keywords')
            WITH ORDINALITY AS keyword_item(keyword_value, ordinality)
        WHERE keyword_value #>> '{}' IS NOT NULL
        ON CONFLICT (raw_article_id, keyword_order) DO UPDATE SET
            keyword = EXCLUDED.keyword;
    END IF;

    IF jsonb_typeof(meta -> 'data') = 'array' THEN
        INSERT INTO raw_article_market_data_points (
            raw_article_id,
            point_order,
            point_date,
            open,
            high,
            low,
            close,
            volume,
            change_pct,
            payload
        )
        SELECT
            target_raw_article_id,
            ordinality::INT,
            axis_to_date(point_value ->> 'date'),
            axis_to_numeric(point_value ->> 'open'),
            axis_to_numeric(point_value ->> 'high'),
            axis_to_numeric(point_value ->> 'low'),
            axis_to_numeric(point_value ->> 'close'),
            axis_to_bigint(point_value ->> 'volume'),
            axis_to_numeric(point_value ->> 'change_pct'),
            point_value
        FROM jsonb_array_elements(meta -> 'data')
            WITH ORDINALITY AS point_item(point_value, ordinality)
        WHERE jsonb_typeof(point_value) = 'object'
        ON CONFLICT (raw_article_id, point_order) DO UPDATE SET
            point_date = EXCLUDED.point_date,
            open = EXCLUDED.open,
            high = EXCLUDED.high,
            low = EXCLUDED.low,
            close = EXCLUDED.close,
            volume = EXCLUDED.volume,
            change_pct = EXCLUDED.change_pct,
            payload = EXCLUDED.payload;
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION axis_refresh_raw_article_normalized_metadata_trigger()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM axis_refresh_raw_article_normalized_metadata(NEW.raw_article_id);
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_ram_news_refresh_normalized ON raw_article_metadata_news;
CREATE TRIGGER trg_ram_news_refresh_normalized
AFTER INSERT OR UPDATE OF source_metadata ON raw_article_metadata_news
FOR EACH ROW EXECUTE FUNCTION axis_refresh_raw_article_normalized_metadata_trigger();

DROP TRIGGER IF EXISTS trg_ram_official_refresh_normalized ON raw_article_metadata_official;
CREATE TRIGGER trg_ram_official_refresh_normalized
AFTER INSERT OR UPDATE OF source_metadata ON raw_article_metadata_official
FOR EACH ROW EXECUTE FUNCTION axis_refresh_raw_article_normalized_metadata_trigger();

DROP TRIGGER IF EXISTS trg_ram_company_site_refresh_normalized ON raw_article_metadata_company_site;
CREATE TRIGGER trg_ram_company_site_refresh_normalized
AFTER INSERT OR UPDATE OF source_metadata ON raw_article_metadata_company_site
FOR EACH ROW EXECUTE FUNCTION axis_refresh_raw_article_normalized_metadata_trigger();

DROP TRIGGER IF EXISTS trg_ram_dart_refresh_normalized ON raw_article_metadata_dart;
CREATE TRIGGER trg_ram_dart_refresh_normalized
AFTER INSERT OR UPDATE OF source_metadata ON raw_article_metadata_dart
FOR EACH ROW EXECUTE FUNCTION axis_refresh_raw_article_normalized_metadata_trigger();

DROP TRIGGER IF EXISTS trg_ram_ir_refresh_normalized ON raw_article_metadata_ir;
CREATE TRIGGER trg_ram_ir_refresh_normalized
AFTER INSERT OR UPDATE OF source_metadata ON raw_article_metadata_ir
FOR EACH ROW EXECUTE FUNCTION axis_refresh_raw_article_normalized_metadata_trigger();

DROP TRIGGER IF EXISTS trg_ram_securities_refresh_normalized
ON raw_article_metadata_securities_report;
CREATE TRIGGER trg_ram_securities_refresh_normalized
AFTER INSERT OR UPDATE OF source_metadata ON raw_article_metadata_securities_report
FOR EACH ROW EXECUTE FUNCTION axis_refresh_raw_article_normalized_metadata_trigger();

DROP TRIGGER IF EXISTS trg_ram_trend_refresh_normalized ON raw_article_metadata_trend_report;
CREATE TRIGGER trg_ram_trend_refresh_normalized
AFTER INSERT OR UPDATE OF source_metadata ON raw_article_metadata_trend_report
FOR EACH ROW EXECUTE FUNCTION axis_refresh_raw_article_normalized_metadata_trigger();

DROP TRIGGER IF EXISTS trg_ram_search_trend_refresh_normalized
ON raw_article_metadata_search_trend;
CREATE TRIGGER trg_ram_search_trend_refresh_normalized
AFTER INSERT OR UPDATE OF source_metadata ON raw_article_metadata_search_trend
FOR EACH ROW EXECUTE FUNCTION axis_refresh_raw_article_normalized_metadata_trigger();

DROP TRIGGER IF EXISTS trg_ram_job_refresh_normalized ON raw_article_metadata_job;
CREATE TRIGGER trg_ram_job_refresh_normalized
AFTER INSERT OR UPDATE OF source_metadata ON raw_article_metadata_job
FOR EACH ROW EXECUTE FUNCTION axis_refresh_raw_article_normalized_metadata_trigger();

DROP TRIGGER IF EXISTS trg_ram_market_data_refresh_normalized
ON raw_article_metadata_market_data;
CREATE TRIGGER trg_ram_market_data_refresh_normalized
AFTER INSERT OR UPDATE OF source_metadata ON raw_article_metadata_market_data
FOR EACH ROW EXECUTE FUNCTION axis_refresh_raw_article_normalized_metadata_trigger();

DO $$
DECLARE
    article RECORD;
    expected_parser_results INT;
    actual_parser_results INT;
BEGIN
    FOR article IN
        SELECT raw_article_id
        FROM raw_article_metadata_unified
        WHERE source_metadata <> '{}'::jsonb
    LOOP
        PERFORM axis_refresh_raw_article_normalized_metadata(article.raw_article_id);
    END LOOP;

    SELECT COUNT(*) INTO expected_parser_results
    FROM raw_article_metadata_unified
    WHERE jsonb_typeof(source_metadata -> 'parser_result') = 'object';

    SELECT COUNT(*) INTO actual_parser_results
    FROM raw_article_parse_results;

    IF expected_parser_results <> actual_parser_results THEN
        RAISE EXCEPTION
            'V23 aborted: parser result mismatch expected=% actual=%',
            expected_parser_results,
            actual_parser_results;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM raw_article_metadata_unified mu
        WHERE jsonb_typeof(mu.source_metadata -> 'keywords') = 'array'
          AND jsonb_array_length(mu.source_metadata -> 'keywords') > 0
          AND NOT EXISTS (
              SELECT 1
              FROM raw_article_search_trend_keywords k
              WHERE k.raw_article_id = mu.raw_article_id
          )
    ) THEN
        RAISE EXCEPTION 'V23 aborted: search trend keyword rows missing';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM raw_article_metadata_unified mu
        WHERE jsonb_typeof(mu.source_metadata -> 'image_urls') = 'array'
          AND jsonb_array_length(mu.source_metadata -> 'image_urls') > 0
          AND NOT EXISTS (
              SELECT 1
              FROM raw_article_media_assets ma
              WHERE ma.raw_article_id = mu.raw_article_id
                AND ma.source_field = 'image_urls'
          )
    ) THEN
        RAISE EXCEPTION 'V23 aborted: image_url media rows missing';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM raw_article_metadata_unified mu
        WHERE jsonb_typeof(mu.source_metadata -> 'data') = 'array'
          AND jsonb_array_length(mu.source_metadata -> 'data') > 0
          AND NOT EXISTS (
              SELECT 1
              FROM raw_article_market_data_points mdp
              WHERE mdp.raw_article_id = mu.raw_article_id
          )
    ) THEN
        RAISE EXCEPTION 'V23 aborted: market data point rows missing';
    END IF;
END $$;
