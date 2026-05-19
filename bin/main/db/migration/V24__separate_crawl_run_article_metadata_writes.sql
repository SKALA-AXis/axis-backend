-- V24: move crawl execution context out of raw_articles writer path.
--
-- raw_articles remains the canonical article record. crawl_runs describes
-- execution windows, and crawl_run_articles records which URL/article was seen
-- during each run. Source-specific payloads continue to live in
-- raw_article_metadata_* tables.

CREATE TABLE IF NOT EXISTS crawl_run_articles (
    id BIGSERIAL PRIMARY KEY,
    crawl_run_id UUID NOT NULL,
    raw_article_id BIGINT,
    url TEXT NOT NULL,
    url_hash VARCHAR(32) NOT NULL,
    discovered_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    action VARCHAR(30) NOT NULL,
    fetch_status VARCHAR(30),
    error_message TEXT,
    source_rank INT,
    raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_crawl_run_articles_run
        FOREIGN KEY (crawl_run_id) REFERENCES crawl_runs(id) ON DELETE CASCADE,
    CONSTRAINT fk_crawl_run_articles_article
        FOREIGN KEY (raw_article_id) REFERENCES raw_articles(id) ON DELETE SET NULL,
    CONSTRAINT uq_crawl_run_articles_run_url
        UNIQUE (crawl_run_id, url_hash)
);

CREATE INDEX IF NOT EXISTS idx_crawl_run_articles_run
    ON crawl_run_articles (crawl_run_id);
CREATE INDEX IF NOT EXISTS idx_crawl_run_articles_article
    ON crawl_run_articles (raw_article_id);
CREATE INDEX IF NOT EXISTS idx_crawl_run_articles_action
    ON crawl_run_articles (action);
CREATE INDEX IF NOT EXISTS idx_crawl_run_articles_url_hash
    ON crawl_run_articles (url_hash);

INSERT INTO crawl_run_articles (
    crawl_run_id,
    raw_article_id,
    url,
    url_hash,
    discovered_at,
    action,
    fetch_status,
    raw_payload
)
SELECT
    ra.crawl_run_id,
    ra.id,
    ra.url,
    ra.url_hash,
    COALESCE(ra.collected_at, ra.created_at, NOW()),
    'legacy_link',
    ra.crawl_status,
    '{}'::jsonb
FROM raw_articles ra
WHERE ra.crawl_run_id IS NOT NULL
ON CONFLICT (crawl_run_id, url_hash) DO NOTHING;

-- SourceType includes "social"; keep the source metadata split complete even
-- if the table is currently sparsely used.
CREATE TABLE IF NOT EXISTS raw_article_metadata_social (
    raw_article_id BIGINT PRIMARY KEY,
    source_metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    platform TEXT GENERATED ALWAYS AS (source_metadata ->> 'platform') STORED,
    author TEXT GENERATED ALWAYS AS (source_metadata ->> 'author') STORED,
    engagement_count TEXT GENERATED ALWAYS AS (source_metadata ->> 'engagement_count') STORED,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_raw_article_metadata_social_article
        FOREIGN KEY (raw_article_id) REFERENCES raw_articles(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_ram_social_source_metadata
    ON raw_article_metadata_social USING GIN(source_metadata);
CREATE INDEX IF NOT EXISTS idx_ram_social_platform
    ON raw_article_metadata_social (platform);

INSERT INTO raw_article_metadata_social (raw_article_id, source_metadata)
SELECT id, axis_raw_article_source_metadata(metadata)
FROM raw_articles
WHERE source_type = 'social'
ON CONFLICT (raw_article_id) DO UPDATE
SET source_metadata = CASE
        WHEN EXCLUDED.source_metadata = '{}'::jsonb
            THEN raw_article_metadata_social.source_metadata
        ELSE raw_article_metadata_social.source_metadata || EXCLUDED.source_metadata
    END,
    updated_at = NOW();

DROP TRIGGER IF EXISTS trg_ram_social_refresh_normalized
ON raw_article_metadata_social;
CREATE TRIGGER trg_ram_social_refresh_normalized
AFTER INSERT OR UPDATE OF source_metadata ON raw_article_metadata_social
FOR EACH ROW EXECUTE FUNCTION axis_refresh_raw_article_normalized_metadata_trigger();

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
            WHEN 'social' THEN social.source_metadata
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
            WHEN 'social' THEN social.source_metadata
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
    ON market_data.raw_article_id = ra.id
LEFT JOIN raw_article_metadata_social social
    ON social.raw_article_id = ra.id;
