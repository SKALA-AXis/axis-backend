-- V22: split raw_articles.metadata into source-specific one-to-one tables.
--
-- Motivation:
--   raw_articles.metadata currently mixes common crawl/provenance fields,
--   source payloads, and parser outputs. Keep raw_articles lean and move
--   source-shaped payloads to typed child tables while preserving a unified
--   compatibility view for existing readers.

CREATE OR REPLACE FUNCTION axis_raw_article_common_metadata(input_metadata JSONB)
RETURNS JSONB
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT COALESCE(jsonb_object_agg(e.key, e.value), '{}'::jsonb)
    FROM jsonb_each(COALESCE(input_metadata, '{}'::jsonb)) AS e(key, value)
    WHERE e.key = ANY (
        ARRAY[
            'url_hash',
            'company_tier',
            'peer_id',
            'topic_scope',
            'company_scope',
            'company_fallback',
            'collection_mode',
            'crawl_run_id',
            'crawl_source_name',
            'track',
            'window_start',
            'window_end',
            'link_check',
            'document_scope',
            'preprocess_note',
            'signal_scope',
            'skip_reason',
            'matched_companies',
            'matched_sectors',
            'primary_company'
        ]::text[]
    );
$$;

CREATE OR REPLACE FUNCTION axis_raw_article_source_metadata(input_metadata JSONB)
RETURNS JSONB
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT COALESCE(jsonb_object_agg(e.key, e.value), '{}'::jsonb)
    FROM jsonb_each(COALESCE(input_metadata, '{}'::jsonb)) AS e(key, value)
    WHERE NOT e.key = ANY (
        ARRAY[
            'url_hash',
            'company_tier',
            'peer_id',
            'topic_scope',
            'company_scope',
            'company_fallback',
            'collection_mode',
            'crawl_run_id',
            'crawl_source_name',
            'track',
            'window_start',
            'window_end',
            'link_check',
            'document_scope',
            'preprocess_note',
            'signal_scope',
            'skip_reason',
            'matched_companies',
            'matched_sectors',
            'primary_company'
        ]::text[]
    );
$$;

CREATE TABLE IF NOT EXISTS raw_article_metadata_news (
    raw_article_id BIGINT PRIMARY KEY,
    source_metadata JSONB NOT NULL DEFAULT '{}',
    search_query TEXT GENERATED ALWAYS AS (source_metadata ->> 'search_query') STORED,
    body_fetch_status TEXT GENERATED ALWAYS AS (source_metadata ->> 'body_fetch_status') STORED,
    subtitle TEXT GENERATED ALWAYS AS (source_metadata ->> 'subtitle') STORED,
    sector TEXT GENERATED ALWAYS AS (source_metadata ->> 'sector') STORED,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_raw_article_metadata_news_article
        FOREIGN KEY (raw_article_id) REFERENCES raw_articles(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS raw_article_metadata_official (
    raw_article_id BIGINT PRIMARY KEY,
    source_metadata JSONB NOT NULL DEFAULT '{}',
    company_name TEXT GENERATED ALWAYS AS (source_metadata ->> 'company_name') STORED,
    list_url TEXT GENERATED ALWAYS AS (source_metadata ->> 'list_url') STORED,
    body_fetch_status TEXT GENERATED ALWAYS AS (source_metadata ->> 'body_fetch_status') STORED,
    source_key TEXT GENERATED ALWAYS AS (source_metadata ->> 'source_key') STORED,
    source_url TEXT GENERATED ALWAYS AS (source_metadata ->> 'source_url') STORED,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_raw_article_metadata_official_article
        FOREIGN KEY (raw_article_id) REFERENCES raw_articles(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS raw_article_metadata_company_site (
    raw_article_id BIGINT PRIMARY KEY,
    source_metadata JSONB NOT NULL DEFAULT '{}',
    page_kind TEXT GENERATED ALWAYS AS (source_metadata ->> 'page_kind') STORED,
    source_family TEXT GENERATED ALWAYS AS (source_metadata ->> 'source_family') STORED,
    content_hash TEXT GENERATED ALWAYS AS (source_metadata ->> 'content_hash') STORED,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_raw_article_metadata_company_site_article
        FOREIGN KEY (raw_article_id) REFERENCES raw_articles(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS raw_article_metadata_dart (
    raw_article_id BIGINT PRIMARY KEY,
    source_metadata JSONB NOT NULL DEFAULT '{}',
    corp_code TEXT GENERATED ALWAYS AS (source_metadata ->> 'corp_code') STORED,
    receipt_no TEXT GENERATED ALWAYS AS (source_metadata ->> 'receipt_no') STORED,
    rcept_no TEXT GENERATED ALWAYS AS (source_metadata ->> 'rcept_no') STORED,
    corp_name TEXT GENERATED ALWAYS AS (source_metadata ->> 'corp_name') STORED,
    stock_code TEXT GENERATED ALWAYS AS (source_metadata ->> 'stock_code') STORED,
    report_name TEXT GENERATED ALWAYS AS (source_metadata ->> 'report_name') STORED,
    rcept_dt TEXT GENERATED ALWAYS AS (source_metadata ->> 'rcept_dt') STORED,
    disclosure_type TEXT GENERATED ALWAYS AS (source_metadata ->> 'disclosure_type') STORED,
    period TEXT GENERATED ALWAYS AS (source_metadata ->> 'period') STORED,
    parser_quality_label TEXT GENERATED ALWAYS AS (source_metadata ->> 'parser_quality_label') STORED,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_raw_article_metadata_dart_article
        FOREIGN KEY (raw_article_id) REFERENCES raw_articles(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS raw_article_metadata_ir (
    raw_article_id BIGINT PRIMARY KEY,
    source_metadata JSONB NOT NULL DEFAULT '{}',
    source_page TEXT GENERATED ALWAYS AS (source_metadata ->> 'source_page') STORED,
    detail_url TEXT GENERATED ALWAYS AS (source_metadata ->> 'detail_url') STORED,
    pdf_url TEXT GENERATED ALWAYS AS (source_metadata ->> 'pdf_url') STORED,
    period TEXT GENERATED ALWAYS AS (source_metadata ->> 'period') STORED,
    parser_quality_label TEXT GENERATED ALWAYS AS (source_metadata ->> 'parser_quality_label') STORED,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_raw_article_metadata_ir_article
        FOREIGN KEY (raw_article_id) REFERENCES raw_articles(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS raw_article_metadata_securities_report (
    raw_article_id BIGINT PRIMARY KEY,
    source_metadata JSONB NOT NULL DEFAULT '{}',
    report_type TEXT GENERATED ALWAYS AS (source_metadata ->> 'report_type') STORED,
    firm TEXT GENERATED ALWAYS AS (source_metadata ->> 'firm') STORED,
    item_code TEXT GENERATED ALWAYS AS (source_metadata ->> 'item_code') STORED,
    pdf_url TEXT GENERATED ALWAYS AS (source_metadata ->> 'pdf_url') STORED,
    parser_quality_label TEXT GENERATED ALWAYS AS (source_metadata ->> 'parser_quality_label') STORED,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_raw_article_metadata_securities_report_article
        FOREIGN KEY (raw_article_id) REFERENCES raw_articles(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS raw_article_metadata_trend_report (
    raw_article_id BIGINT PRIMARY KEY,
    source_metadata JSONB NOT NULL DEFAULT '{}',
    collection TEXT GENERATED ALWAYS AS (source_metadata ->> 'collection') STORED,
    month TEXT GENERATED ALWAYS AS (source_metadata ->> 'month') STORED,
    issue_title TEXT GENERATED ALWAYS AS (source_metadata ->> 'issue_title') STORED,
    pdf_title TEXT GENERATED ALWAYS AS (source_metadata ->> 'pdf_title') STORED,
    pdf_url TEXT GENERATED ALWAYS AS (source_metadata ->> 'pdf_url') STORED,
    sector_filter_status TEXT GENERATED ALWAYS AS (source_metadata ->> 'sector_filter_status') STORED,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_raw_article_metadata_trend_report_article
        FOREIGN KEY (raw_article_id) REFERENCES raw_articles(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS raw_article_metadata_search_trend (
    raw_article_id BIGINT PRIMARY KEY,
    source_metadata JSONB NOT NULL DEFAULT '{}',
    group_name TEXT GENERATED ALWAYS AS (source_metadata ->> 'group_name') STORED,
    period TEXT GENERATED ALWAYS AS (source_metadata ->> 'period') STORED,
    time_unit TEXT GENERATED ALWAYS AS (source_metadata ->> 'time_unit') STORED,
    crawl_type TEXT GENERATED ALWAYS AS (source_metadata ->> 'crawl_type') STORED,
    source_name TEXT GENERATED ALWAYS AS (source_metadata ->> 'source') STORED,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_raw_article_metadata_search_trend_article
        FOREIGN KEY (raw_article_id) REFERENCES raw_articles(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS raw_article_metadata_job (
    raw_article_id BIGINT PRIMARY KEY,
    source_metadata JSONB NOT NULL DEFAULT '{}',
    emp_seqno TEXT GENERATED ALWAYS AS (source_metadata ->> 'emp_seqno') STORED,
    peer_company TEXT GENERATED ALWAYS AS (source_metadata ->> 'peer_company') STORED,
    company_name TEXT GENERATED ALWAYS AS (source_metadata ->> 'company') STORED,
    job_title TEXT GENERATED ALWAYS AS (source_metadata ->> 'job_title') STORED,
    start_date TEXT GENERATED ALWAYS AS (source_metadata ->> 'start_date') STORED,
    end_date TEXT GENERATED ALWAYS AS (source_metadata ->> 'end_date') STORED,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_raw_article_metadata_job_article
        FOREIGN KEY (raw_article_id) REFERENCES raw_articles(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS raw_article_metadata_market_data (
    raw_article_id BIGINT PRIMARY KEY,
    source_metadata JSONB NOT NULL DEFAULT '{}',
    peer_id TEXT GENERATED ALWAYS AS (source_metadata ->> 'peer_id') STORED,
    missing_policy TEXT GENERATED ALWAYS AS (source_metadata ->> 'missing_policy') STORED,
    source_type_detail TEXT GENERATED ALWAYS AS (source_metadata ->> 'source_type') STORED,
    content_type_detail TEXT GENERATED ALWAYS AS (source_metadata ->> 'content_type') STORED,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_raw_article_metadata_market_data_article
        FOREIGN KEY (raw_article_id) REFERENCES raw_articles(id) ON DELETE CASCADE
);

INSERT INTO raw_article_metadata_news (raw_article_id, source_metadata)
SELECT id, axis_raw_article_source_metadata(metadata)
FROM raw_articles
WHERE source_type = 'news'
ON CONFLICT (raw_article_id) DO UPDATE
SET source_metadata = CASE
        WHEN EXCLUDED.source_metadata = '{}'::jsonb
            THEN raw_article_metadata_news.source_metadata
        ELSE raw_article_metadata_news.source_metadata || EXCLUDED.source_metadata
    END,
    updated_at = NOW();

INSERT INTO raw_article_metadata_official (raw_article_id, source_metadata)
SELECT id, axis_raw_article_source_metadata(metadata)
FROM raw_articles
WHERE source_type = 'official'
ON CONFLICT (raw_article_id) DO UPDATE
SET source_metadata = CASE
        WHEN EXCLUDED.source_metadata = '{}'::jsonb
            THEN raw_article_metadata_official.source_metadata
        ELSE raw_article_metadata_official.source_metadata || EXCLUDED.source_metadata
    END,
    updated_at = NOW();

INSERT INTO raw_article_metadata_company_site (raw_article_id, source_metadata)
SELECT id, axis_raw_article_source_metadata(metadata)
FROM raw_articles
WHERE source_type = 'company_site'
ON CONFLICT (raw_article_id) DO UPDATE
SET source_metadata = CASE
        WHEN EXCLUDED.source_metadata = '{}'::jsonb
            THEN raw_article_metadata_company_site.source_metadata
        ELSE raw_article_metadata_company_site.source_metadata || EXCLUDED.source_metadata
    END,
    updated_at = NOW();

INSERT INTO raw_article_metadata_dart (raw_article_id, source_metadata)
SELECT id, axis_raw_article_source_metadata(metadata)
FROM raw_articles
WHERE source_type = 'dart'
ON CONFLICT (raw_article_id) DO UPDATE
SET source_metadata = CASE
        WHEN EXCLUDED.source_metadata = '{}'::jsonb
            THEN raw_article_metadata_dart.source_metadata
        ELSE raw_article_metadata_dart.source_metadata || EXCLUDED.source_metadata
    END,
    updated_at = NOW();

INSERT INTO raw_article_metadata_ir (raw_article_id, source_metadata)
SELECT id, axis_raw_article_source_metadata(metadata)
FROM raw_articles
WHERE source_type = 'ir'
ON CONFLICT (raw_article_id) DO UPDATE
SET source_metadata = CASE
        WHEN EXCLUDED.source_metadata = '{}'::jsonb
            THEN raw_article_metadata_ir.source_metadata
        ELSE raw_article_metadata_ir.source_metadata || EXCLUDED.source_metadata
    END,
    updated_at = NOW();

INSERT INTO raw_article_metadata_securities_report (raw_article_id, source_metadata)
SELECT id, axis_raw_article_source_metadata(metadata)
FROM raw_articles
WHERE source_type = 'securities_report'
ON CONFLICT (raw_article_id) DO UPDATE
SET source_metadata = CASE
        WHEN EXCLUDED.source_metadata = '{}'::jsonb
            THEN raw_article_metadata_securities_report.source_metadata
        ELSE raw_article_metadata_securities_report.source_metadata || EXCLUDED.source_metadata
    END,
    updated_at = NOW();

INSERT INTO raw_article_metadata_trend_report (raw_article_id, source_metadata)
SELECT id, axis_raw_article_source_metadata(metadata)
FROM raw_articles
WHERE source_type = 'trend_report'
ON CONFLICT (raw_article_id) DO UPDATE
SET source_metadata = CASE
        WHEN EXCLUDED.source_metadata = '{}'::jsonb
            THEN raw_article_metadata_trend_report.source_metadata
        ELSE raw_article_metadata_trend_report.source_metadata || EXCLUDED.source_metadata
    END,
    updated_at = NOW();

INSERT INTO raw_article_metadata_search_trend (raw_article_id, source_metadata)
SELECT id, axis_raw_article_source_metadata(metadata)
FROM raw_articles
WHERE source_type = 'search_trend'
ON CONFLICT (raw_article_id) DO UPDATE
SET source_metadata = CASE
        WHEN EXCLUDED.source_metadata = '{}'::jsonb
            THEN raw_article_metadata_search_trend.source_metadata
        ELSE raw_article_metadata_search_trend.source_metadata || EXCLUDED.source_metadata
    END,
    updated_at = NOW();

INSERT INTO raw_article_metadata_job (raw_article_id, source_metadata)
SELECT id, axis_raw_article_source_metadata(metadata)
FROM raw_articles
WHERE source_type = 'job'
ON CONFLICT (raw_article_id) DO UPDATE
SET source_metadata = CASE
        WHEN EXCLUDED.source_metadata = '{}'::jsonb
            THEN raw_article_metadata_job.source_metadata
        ELSE raw_article_metadata_job.source_metadata || EXCLUDED.source_metadata
    END,
    updated_at = NOW();

INSERT INTO raw_article_metadata_market_data (raw_article_id, source_metadata)
SELECT id, axis_raw_article_source_metadata(metadata)
FROM raw_articles
WHERE source_type = 'market_data'
ON CONFLICT (raw_article_id) DO UPDATE
SET source_metadata = CASE
        WHEN EXCLUDED.source_metadata = '{}'::jsonb
            THEN raw_article_metadata_market_data.source_metadata
        ELSE raw_article_metadata_market_data.source_metadata || EXCLUDED.source_metadata
    END,
    updated_at = NOW();

UPDATE raw_articles
SET metadata = axis_raw_article_common_metadata(metadata)
WHERE metadata <> axis_raw_article_common_metadata(metadata);

CREATE INDEX IF NOT EXISTS idx_ram_news_source_metadata
    ON raw_article_metadata_news USING GIN(source_metadata);
CREATE INDEX IF NOT EXISTS idx_ram_news_search_query
    ON raw_article_metadata_news (search_query);

CREATE INDEX IF NOT EXISTS idx_ram_official_source_metadata
    ON raw_article_metadata_official USING GIN(source_metadata);
CREATE INDEX IF NOT EXISTS idx_ram_official_company_name
    ON raw_article_metadata_official (company_name);

CREATE INDEX IF NOT EXISTS idx_ram_company_site_source_metadata
    ON raw_article_metadata_company_site USING GIN(source_metadata);
CREATE INDEX IF NOT EXISTS idx_ram_company_site_page_kind
    ON raw_article_metadata_company_site (page_kind);
CREATE INDEX IF NOT EXISTS idx_ram_company_site_source_family
    ON raw_article_metadata_company_site (source_family);

CREATE INDEX IF NOT EXISTS idx_ram_dart_source_metadata
    ON raw_article_metadata_dart USING GIN(source_metadata);
CREATE INDEX IF NOT EXISTS idx_ram_dart_rcept_no
    ON raw_article_metadata_dart (rcept_no);
CREATE INDEX IF NOT EXISTS idx_ram_dart_period
    ON raw_article_metadata_dart (period);

CREATE INDEX IF NOT EXISTS idx_ram_ir_source_metadata
    ON raw_article_metadata_ir USING GIN(source_metadata);
CREATE INDEX IF NOT EXISTS idx_ram_ir_pdf_url
    ON raw_article_metadata_ir (pdf_url);
CREATE INDEX IF NOT EXISTS idx_ram_ir_period
    ON raw_article_metadata_ir (period);

CREATE INDEX IF NOT EXISTS idx_ram_securities_source_metadata
    ON raw_article_metadata_securities_report USING GIN(source_metadata);
CREATE INDEX IF NOT EXISTS idx_ram_securities_item_code
    ON raw_article_metadata_securities_report (item_code);
CREATE INDEX IF NOT EXISTS idx_ram_securities_firm
    ON raw_article_metadata_securities_report (firm);

CREATE INDEX IF NOT EXISTS idx_ram_trend_source_metadata
    ON raw_article_metadata_trend_report USING GIN(source_metadata);
CREATE INDEX IF NOT EXISTS idx_ram_trend_collection
    ON raw_article_metadata_trend_report (collection);

CREATE INDEX IF NOT EXISTS idx_ram_search_trend_source_metadata
    ON raw_article_metadata_search_trend USING GIN(source_metadata);
CREATE INDEX IF NOT EXISTS idx_ram_search_trend_group_period
    ON raw_article_metadata_search_trend (group_name, period);

CREATE INDEX IF NOT EXISTS idx_ram_job_source_metadata
    ON raw_article_metadata_job USING GIN(source_metadata);
CREATE INDEX IF NOT EXISTS idx_ram_job_emp_seqno
    ON raw_article_metadata_job (emp_seqno);

CREATE INDEX IF NOT EXISTS idx_ram_market_data_source_metadata
    ON raw_article_metadata_market_data USING GIN(source_metadata);
CREATE INDEX IF NOT EXISTS idx_ram_market_data_peer_id
    ON raw_article_metadata_market_data (peer_id);

CREATE OR REPLACE VIEW raw_article_metadata_unified AS
SELECT
    ra.id AS raw_article_id,
    ra.source_type,
    ra.source_name,
    ra.metadata AS common_metadata,
    COALESCE(
        CASE ra.source_type
            WHEN 'news' THEN news.source_metadata
            WHEN 'official' THEN official.source_metadata
            WHEN 'company_site' THEN company_site.source_metadata
            WHEN 'dart' THEN dart.source_metadata
            WHEN 'ir' THEN ir.source_metadata
            WHEN 'securities_report' THEN securities_report.source_metadata
            WHEN 'trend_report' THEN trend_report.source_metadata
            WHEN 'search_trend' THEN search_trend.source_metadata
            WHEN 'job' THEN job.source_metadata
            WHEN 'market_data' THEN market_data.source_metadata
            ELSE '{}'::jsonb
        END,
        '{}'::jsonb
    ) AS source_metadata,
    ra.metadata || COALESCE(
        CASE ra.source_type
            WHEN 'news' THEN news.source_metadata
            WHEN 'official' THEN official.source_metadata
            WHEN 'company_site' THEN company_site.source_metadata
            WHEN 'dart' THEN dart.source_metadata
            WHEN 'ir' THEN ir.source_metadata
            WHEN 'securities_report' THEN securities_report.source_metadata
            WHEN 'trend_report' THEN trend_report.source_metadata
            WHEN 'search_trend' THEN search_trend.source_metadata
            WHEN 'job' THEN job.source_metadata
            WHEN 'market_data' THEN market_data.source_metadata
            ELSE '{}'::jsonb
        END,
        '{}'::jsonb
    ) AS metadata
FROM raw_articles ra
LEFT JOIN raw_article_metadata_news news
    ON news.raw_article_id = ra.id
LEFT JOIN raw_article_metadata_official official
    ON official.raw_article_id = ra.id
LEFT JOIN raw_article_metadata_company_site company_site
    ON company_site.raw_article_id = ra.id
LEFT JOIN raw_article_metadata_dart dart
    ON dart.raw_article_id = ra.id
LEFT JOIN raw_article_metadata_ir ir
    ON ir.raw_article_id = ra.id
LEFT JOIN raw_article_metadata_securities_report securities_report
    ON securities_report.raw_article_id = ra.id
LEFT JOIN raw_article_metadata_trend_report trend_report
    ON trend_report.raw_article_id = ra.id
LEFT JOIN raw_article_metadata_search_trend search_trend
    ON search_trend.raw_article_id = ra.id
LEFT JOIN raw_article_metadata_job job
    ON job.raw_article_id = ra.id
LEFT JOIN raw_article_metadata_market_data market_data
    ON market_data.raw_article_id = ra.id;

DO $$
DECLARE
    missing_count INT;
    non_common_count INT;
BEGIN
    SELECT COUNT(*) INTO missing_count
    FROM raw_articles ra
    LEFT JOIN raw_article_metadata_news m ON m.raw_article_id = ra.id
    WHERE ra.source_type = 'news'
      AND m.raw_article_id IS NULL;
    IF missing_count > 0 THEN
        RAISE EXCEPTION 'V22 aborted: news metadata rows missing: %', missing_count;
    END IF;

    SELECT COUNT(*) INTO missing_count
    FROM raw_articles ra
    LEFT JOIN raw_article_metadata_official m ON m.raw_article_id = ra.id
    WHERE ra.source_type = 'official'
      AND m.raw_article_id IS NULL;
    IF missing_count > 0 THEN
        RAISE EXCEPTION 'V22 aborted: official metadata rows missing: %', missing_count;
    END IF;

    SELECT COUNT(*) INTO missing_count
    FROM raw_articles ra
    LEFT JOIN raw_article_metadata_company_site m ON m.raw_article_id = ra.id
    WHERE ra.source_type = 'company_site'
      AND m.raw_article_id IS NULL;
    IF missing_count > 0 THEN
        RAISE EXCEPTION 'V22 aborted: company_site metadata rows missing: %', missing_count;
    END IF;

    SELECT COUNT(*) INTO missing_count
    FROM raw_articles ra
    LEFT JOIN raw_article_metadata_dart m ON m.raw_article_id = ra.id
    WHERE ra.source_type = 'dart'
      AND m.raw_article_id IS NULL;
    IF missing_count > 0 THEN
        RAISE EXCEPTION 'V22 aborted: dart metadata rows missing: %', missing_count;
    END IF;

    SELECT COUNT(*) INTO missing_count
    FROM raw_articles ra
    LEFT JOIN raw_article_metadata_ir m ON m.raw_article_id = ra.id
    WHERE ra.source_type = 'ir'
      AND m.raw_article_id IS NULL;
    IF missing_count > 0 THEN
        RAISE EXCEPTION 'V22 aborted: ir metadata rows missing: %', missing_count;
    END IF;

    SELECT COUNT(*) INTO missing_count
    FROM raw_articles ra
    LEFT JOIN raw_article_metadata_securities_report m ON m.raw_article_id = ra.id
    WHERE ra.source_type = 'securities_report'
      AND m.raw_article_id IS NULL;
    IF missing_count > 0 THEN
        RAISE EXCEPTION 'V22 aborted: securities_report metadata rows missing: %', missing_count;
    END IF;

    SELECT COUNT(*) INTO missing_count
    FROM raw_articles ra
    LEFT JOIN raw_article_metadata_trend_report m ON m.raw_article_id = ra.id
    WHERE ra.source_type = 'trend_report'
      AND m.raw_article_id IS NULL;
    IF missing_count > 0 THEN
        RAISE EXCEPTION 'V22 aborted: trend_report metadata rows missing: %', missing_count;
    END IF;

    SELECT COUNT(*) INTO missing_count
    FROM raw_articles ra
    LEFT JOIN raw_article_metadata_search_trend m ON m.raw_article_id = ra.id
    WHERE ra.source_type = 'search_trend'
      AND m.raw_article_id IS NULL;
    IF missing_count > 0 THEN
        RAISE EXCEPTION 'V22 aborted: search_trend metadata rows missing: %', missing_count;
    END IF;

    SELECT COUNT(*) INTO missing_count
    FROM raw_articles ra
    LEFT JOIN raw_article_metadata_job m ON m.raw_article_id = ra.id
    WHERE ra.source_type = 'job'
      AND m.raw_article_id IS NULL;
    IF missing_count > 0 THEN
        RAISE EXCEPTION 'V22 aborted: job metadata rows missing: %', missing_count;
    END IF;

    SELECT COUNT(*) INTO missing_count
    FROM raw_articles ra
    LEFT JOIN raw_article_metadata_market_data m ON m.raw_article_id = ra.id
    WHERE ra.source_type = 'market_data'
      AND m.raw_article_id IS NULL;
    IF missing_count > 0 THEN
        RAISE EXCEPTION 'V22 aborted: market_data metadata rows missing: %', missing_count;
    END IF;

    SELECT COUNT(*) INTO non_common_count
    FROM raw_articles
    WHERE metadata <> axis_raw_article_common_metadata(metadata);
    IF non_common_count > 0 THEN
        RAISE EXCEPTION 'V22 aborted: raw_articles.metadata still has non-common rows: %',
            non_common_count;
    END IF;
END $$;
