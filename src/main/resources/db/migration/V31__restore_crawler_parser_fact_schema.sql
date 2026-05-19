-- V31: restore crawler/parser fact tables after V30 product-schema collapse.
--
-- V30 archived several operational/fact tables into legacy_records and folded
-- some values into raw_articles JSONB read-model columns. The crawler/parser
-- pipeline needs those tables as first-class operational storage again.

CREATE TABLE IF NOT EXISTS legacy_records (
    id BIGSERIAL PRIMARY KEY,
    source_table VARCHAR(100) NOT NULL,
    source_pk TEXT,
    owner_table VARCHAR(100),
    owner_id TEXT,
    payload JSONB NOT NULL,
    archived_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_legacy_records_source_pk
    ON legacy_records (source_table, source_pk)
    WHERE source_pk IS NOT NULL;

CREATE TABLE IF NOT EXISTS crawl_runs (
    id UUID PRIMARY KEY,
    run_type VARCHAR(30) NOT NULL,
    source_name VARCHAR(100) NOT NULL,
    window_start DATE NOT NULL,
    window_end DATE NOT NULL,
    status VARCHAR(30) NOT NULL,
    inserted_count INT NOT NULL DEFAULT 0,
    skipped_count INT NOT NULL DEFAULT 0,
    error_message TEXT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    finished_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_crawl_runs_source_started
    ON crawl_runs (source_name, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_crawl_runs_status
    ON crawl_runs (status);
CREATE INDEX IF NOT EXISTS idx_crawl_runs_window
    ON crawl_runs (window_start, window_end);

CREATE TABLE IF NOT EXISTS crawl_run_articles (
    id BIGSERIAL PRIMARY KEY,
    crawl_run_id UUID NOT NULL REFERENCES crawl_runs(id) ON DELETE CASCADE,
    raw_article_id BIGINT REFERENCES raw_articles(id) ON DELETE SET NULL,
    url TEXT NOT NULL,
    url_hash VARCHAR(32) NOT NULL,
    discovered_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    action VARCHAR(30) NOT NULL,
    fetch_status VARCHAR(30),
    error_message TEXT,
    source_rank INT,
    raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_crawl_run_articles_run_url UNIQUE (crawl_run_id, url_hash)
);

CREATE INDEX IF NOT EXISTS idx_crawl_run_articles_run
    ON crawl_run_articles (crawl_run_id);
CREATE INDEX IF NOT EXISTS idx_crawl_run_articles_article
    ON crawl_run_articles (raw_article_id);
CREATE INDEX IF NOT EXISTS idx_crawl_run_articles_action
    ON crawl_run_articles (action);
CREATE INDEX IF NOT EXISTS idx_crawl_run_articles_url_hash
    ON crawl_run_articles (url_hash);

CREATE TABLE IF NOT EXISTS raw_article_financial_metrics (
    id BIGSERIAL PRIMARY KEY,
    raw_article_id BIGINT NOT NULL REFERENCES raw_articles(id) ON DELETE CASCADE,
    metric_uid TEXT NOT NULL,
    source_type VARCHAR(50) NOT NULL,
    source_name VARCHAR(100),
    peer_id VARCHAR(50),
    period TEXT,
    period_year INT,
    period_quarter INT,
    period_type TEXT,
    metric_name TEXT NOT NULL,
    metric_label TEXT,
    metric_scope TEXT,
    business_area TEXT,
    value_numeric NUMERIC(24, 6),
    value_krwbn DOUBLE PRECISION,
    value_krw NUMERIC(24, 2),
    unit TEXT,
    currency VARCHAR(10) DEFAULT 'KRW',
    source_page INT,
    source_table_uid TEXT,
    source_chunk_uid TEXT,
    confidence DOUBLE PRECISION,
    extraction_method TEXT,
    evidence_text TEXT,
    payload JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_raw_article_financial_metric UNIQUE (raw_article_id, metric_uid),
    CONSTRAINT chk_raw_article_financial_metrics_confidence
        CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1))
);

CREATE INDEX IF NOT EXISTS idx_raw_article_financial_metrics_peer_period
    ON raw_article_financial_metrics (peer_id, period);
CREATE INDEX IF NOT EXISTS idx_raw_article_financial_metrics_metric
    ON raw_article_financial_metrics (metric_name, period);
CREATE INDEX IF NOT EXISTS idx_raw_article_financial_metrics_source
    ON raw_article_financial_metrics (source_type, source_name);
CREATE INDEX IF NOT EXISTS idx_raw_article_financial_metrics_payload_gin
    ON raw_article_financial_metrics USING GIN(payload);

CREATE TABLE IF NOT EXISTS raw_article_business_signals (
    id BIGSERIAL PRIMARY KEY,
    raw_article_id BIGINT NOT NULL REFERENCES raw_articles(id) ON DELETE CASCADE,
    signal_uid TEXT NOT NULL,
    source_type VARCHAR(50) NOT NULL,
    source_name VARCHAR(100),
    peer_id VARCHAR(50),
    period TEXT,
    period_year INT,
    period_quarter INT,
    period_type TEXT,
    business_area TEXT NOT NULL,
    signal_type TEXT NOT NULL,
    sentiment TEXT,
    summary TEXT NOT NULL,
    evidence_text TEXT,
    source_page INT,
    source_chunk_uid TEXT,
    confidence DOUBLE PRECISION,
    extraction_method TEXT,
    payload JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_raw_article_business_signal UNIQUE (raw_article_id, signal_uid),
    CONSTRAINT chk_raw_article_business_signals_confidence
        CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
    CONSTRAINT chk_raw_article_business_signals_sentiment
        CHECK (
            sentiment IS NULL
            OR sentiment IN ('positive', 'neutral', 'negative', 'mixed', 'unknown')
        )
);

CREATE INDEX IF NOT EXISTS idx_raw_article_business_signals_peer_period
    ON raw_article_business_signals (peer_id, period);
CREATE INDEX IF NOT EXISTS idx_raw_article_business_signals_area_type
    ON raw_article_business_signals (business_area, signal_type);
CREATE INDEX IF NOT EXISTS idx_raw_article_business_signals_source
    ON raw_article_business_signals (source_type, source_name);
CREATE INDEX IF NOT EXISTS idx_raw_article_business_signals_payload_gin
    ON raw_article_business_signals USING GIN(payload);

CREATE OR REPLACE FUNCTION axis_touch_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_raw_article_financial_metrics_updated_at
    ON raw_article_financial_metrics;
CREATE TRIGGER trg_raw_article_financial_metrics_updated_at
BEFORE UPDATE ON raw_article_financial_metrics
FOR EACH ROW
EXECUTE FUNCTION axis_touch_updated_at();

DROP TRIGGER IF EXISTS trg_raw_article_business_signals_updated_at
    ON raw_article_business_signals;
CREATE TRIGGER trg_raw_article_business_signals_updated_at
BEFORE UPDATE ON raw_article_business_signals
FOR EACH ROW
EXECUTE FUNCTION axis_touch_updated_at();

INSERT INTO crawl_runs (
    id,
    run_type,
    source_name,
    window_start,
    window_end,
    status,
    inserted_count,
    skipped_count,
    error_message,
    started_at,
    finished_at
)
SELECT
    (payload ->> 'id')::uuid,
    COALESCE(NULLIF(payload ->> 'run_type', ''), 'unknown'),
    COALESCE(NULLIF(payload ->> 'source_name', ''), 'unknown'),
    COALESCE(NULLIF(payload ->> 'window_start', '')::date, CURRENT_DATE),
    COALESCE(NULLIF(payload ->> 'window_end', '')::date, CURRENT_DATE),
    COALESCE(NULLIF(payload ->> 'status', ''), 'unknown'),
    COALESCE(NULLIF(payload ->> 'inserted_count', '')::int, 0),
    COALESCE(NULLIF(payload ->> 'skipped_count', '')::int, 0),
    payload ->> 'error_message',
    COALESCE(NULLIF(payload ->> 'started_at', '')::timestamptz, NOW()),
    NULLIF(payload ->> 'finished_at', '')::timestamptz
FROM legacy_records
WHERE source_table = 'crawl_runs'
  AND payload ? 'id'
  AND payload ->> 'id' ~* '^[0-9a-f-]{36}$'
ON CONFLICT (id) DO NOTHING;

INSERT INTO crawl_run_articles (
    id,
    crawl_run_id,
    raw_article_id,
    url,
    url_hash,
    discovered_at,
    action,
    fetch_status,
    error_message,
    source_rank,
    raw_payload,
    created_at
)
SELECT
    (lr.payload ->> 'id')::bigint,
    (lr.payload ->> 'crawl_run_id')::uuid,
    NULLIF(lr.payload ->> 'raw_article_id', '')::bigint,
    COALESCE(NULLIF(lr.payload ->> 'url', ''), 'legacy:missing-url:' || lr.source_pk),
    COALESCE(NULLIF(lr.payload ->> 'url_hash', ''), md5(COALESCE(lr.payload ->> 'url', lr.source_pk))),
    COALESCE(NULLIF(lr.payload ->> 'discovered_at', '')::timestamptz, NOW()),
    COALESCE(NULLIF(lr.payload ->> 'action', ''), 'legacy_restore'),
    lr.payload ->> 'fetch_status',
    lr.payload ->> 'error_message',
    NULLIF(lr.payload ->> 'source_rank', '')::int,
    COALESCE(lr.payload -> 'raw_payload', '{}'::jsonb),
    COALESCE(NULLIF(lr.payload ->> 'created_at', '')::timestamptz, NOW())
FROM legacy_records lr
JOIN crawl_runs cr
    ON cr.id = (lr.payload ->> 'crawl_run_id')::uuid
WHERE lr.source_table = 'crawl_run_articles'
  AND lr.payload ? 'id'
  AND lr.payload ? 'crawl_run_id'
  AND lr.payload ->> 'crawl_run_id' ~* '^[0-9a-f-]{36}$'
ON CONFLICT DO NOTHING;

INSERT INTO raw_article_financial_metrics (
    id,
    raw_article_id,
    metric_uid,
    source_type,
    source_name,
    peer_id,
    period,
    period_year,
    period_quarter,
    period_type,
    metric_name,
    metric_label,
    metric_scope,
    business_area,
    value_numeric,
    value_krwbn,
    value_krw,
    unit,
    currency,
    source_page,
    source_table_uid,
    source_chunk_uid,
    confidence,
    extraction_method,
    evidence_text,
    payload,
    created_at,
    updated_at
)
SELECT
    (lr.payload ->> 'id')::bigint,
    (lr.payload ->> 'raw_article_id')::bigint,
    lr.payload ->> 'metric_uid',
    COALESCE(NULLIF(lr.payload ->> 'source_type', ''), 'unknown'),
    lr.payload ->> 'source_name',
    lr.payload ->> 'peer_id',
    lr.payload ->> 'period',
    NULLIF(lr.payload ->> 'period_year', '')::int,
    NULLIF(lr.payload ->> 'period_quarter', '')::int,
    lr.payload ->> 'period_type',
    COALESCE(NULLIF(lr.payload ->> 'metric_name', ''), 'unknown'),
    lr.payload ->> 'metric_label',
    lr.payload ->> 'metric_scope',
    lr.payload ->> 'business_area',
    NULLIF(lr.payload ->> 'value_numeric', '')::numeric,
    NULLIF(lr.payload ->> 'value_krwbn', '')::double precision,
    NULLIF(lr.payload ->> 'value_krw', '')::numeric,
    lr.payload ->> 'unit',
    COALESCE(NULLIF(lr.payload ->> 'currency', ''), 'KRW'),
    NULLIF(lr.payload ->> 'source_page', '')::int,
    lr.payload ->> 'source_table_uid',
    lr.payload ->> 'source_chunk_uid',
    NULLIF(lr.payload ->> 'confidence', '')::double precision,
    lr.payload ->> 'extraction_method',
    lr.payload ->> 'evidence_text',
    COALESCE(lr.payload -> 'payload', '{}'::jsonb),
    COALESCE(NULLIF(lr.payload ->> 'created_at', '')::timestamptz, NOW()),
    COALESCE(NULLIF(lr.payload ->> 'updated_at', '')::timestamptz, NOW())
FROM legacy_records lr
JOIN raw_articles ra
    ON ra.id = (lr.payload ->> 'raw_article_id')::bigint
WHERE lr.source_table = 'raw_article_financial_metrics'
  AND lr.payload ? 'id'
  AND lr.payload ? 'raw_article_id'
  AND lr.payload ? 'metric_uid'
ON CONFLICT DO NOTHING;

INSERT INTO raw_article_business_signals (
    id,
    raw_article_id,
    signal_uid,
    source_type,
    source_name,
    peer_id,
    period,
    period_year,
    period_quarter,
    period_type,
    business_area,
    signal_type,
    sentiment,
    summary,
    evidence_text,
    source_page,
    source_chunk_uid,
    confidence,
    extraction_method,
    payload,
    created_at,
    updated_at
)
SELECT
    (lr.payload ->> 'id')::bigint,
    (lr.payload ->> 'raw_article_id')::bigint,
    lr.payload ->> 'signal_uid',
    COALESCE(NULLIF(lr.payload ->> 'source_type', ''), 'unknown'),
    lr.payload ->> 'source_name',
    lr.payload ->> 'peer_id',
    lr.payload ->> 'period',
    NULLIF(lr.payload ->> 'period_year', '')::int,
    NULLIF(lr.payload ->> 'period_quarter', '')::int,
    lr.payload ->> 'period_type',
    COALESCE(NULLIF(lr.payload ->> 'business_area', ''), 'unknown'),
    COALESCE(NULLIF(lr.payload ->> 'signal_type', ''), 'unknown'),
    lr.payload ->> 'sentiment',
    COALESCE(NULLIF(lr.payload ->> 'summary', ''), ''),
    lr.payload ->> 'evidence_text',
    NULLIF(lr.payload ->> 'source_page', '')::int,
    lr.payload ->> 'source_chunk_uid',
    NULLIF(lr.payload ->> 'confidence', '')::double precision,
    lr.payload ->> 'extraction_method',
    COALESCE(lr.payload -> 'payload', '{}'::jsonb),
    COALESCE(NULLIF(lr.payload ->> 'created_at', '')::timestamptz, NOW()),
    COALESCE(NULLIF(lr.payload ->> 'updated_at', '')::timestamptz, NOW())
FROM legacy_records lr
JOIN raw_articles ra
    ON ra.id = (lr.payload ->> 'raw_article_id')::bigint
WHERE lr.source_table = 'raw_article_business_signals'
  AND lr.payload ? 'id'
  AND lr.payload ? 'raw_article_id'
  AND lr.payload ? 'signal_uid'
ON CONFLICT DO NOTHING;

DO $$
BEGIN
    IF to_regclass('public.raw_article_source_metadata') IS NOT NULL THEN
        INSERT INTO legacy_records (source_table, source_pk, owner_table, owner_id, payload)
        SELECT
            'raw_article_source_metadata',
            raw_article_id::text,
            'raw_articles',
            raw_article_id::text,
            to_jsonb(raw_article_source_metadata)
        FROM raw_article_source_metadata
        ON CONFLICT (source_table, source_pk) WHERE source_pk IS NOT NULL DO NOTHING;

        UPDATE raw_articles ra
        SET metadata = ra.metadata || sm.source_metadata
        FROM raw_article_source_metadata sm
        WHERE ra.id = sm.raw_article_id
          AND sm.source_metadata <> '{}'::jsonb;
    END IF;
END $$;

CREATE OR REPLACE VIEW raw_article_metadata_unified AS
SELECT
    ra.id AS raw_article_id,
    ra.source_type,
    ra.source_name,
    ra.metadata AS common_metadata,
    '{}'::jsonb AS source_metadata,
    ra.metadata AS metadata
FROM raw_articles ra;

DROP TABLE IF EXISTS raw_article_source_metadata;

ALTER TABLE raw_articles
    DROP COLUMN IF EXISTS peer_company_ids,
    DROP COLUMN IF EXISTS peer_company_links,
    DROP COLUMN IF EXISTS financial_metrics,
    DROP COLUMN IF EXISTS business_signals,
    DROP COLUMN IF EXISTS crawl_events,
    DROP COLUMN IF EXISTS legacy_payload;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_raw_articles_crawl_run'
          AND conrelid = 'raw_articles'::regclass
    ) THEN
        ALTER TABLE raw_articles
            ADD CONSTRAINT fk_raw_articles_crawl_run
            FOREIGN KEY (crawl_run_id)
            REFERENCES crawl_runs(id)
            ON DELETE SET NULL
            NOT VALID;
    END IF;
END $$;

SELECT setval(
    pg_get_serial_sequence('crawl_run_articles', 'id'),
    COALESCE((SELECT MAX(id) FROM crawl_run_articles), 1),
    true
);

SELECT setval(
    pg_get_serial_sequence('raw_article_financial_metrics', 'id'),
    COALESCE((SELECT MAX(id) FROM raw_article_financial_metrics), 1),
    true
);

SELECT setval(
    pg_get_serial_sequence('raw_article_business_signals', 'id'),
    COALESCE((SELECT MAX(id) FROM raw_article_business_signals), 1),
    true
);

COMMENT ON TABLE crawl_runs IS
    'Crawler execution header/state. Restored as first-class operational data after V30.';
COMMENT ON TABLE crawl_run_articles IS
    'URL/article-level crawler execution line items. Restored as first-class operational data after V30.';
COMMENT ON TABLE raw_article_financial_metrics IS
    'IR/DART and document parser numeric facts. Source of truth for reusable financial metrics.';
COMMENT ON TABLE raw_article_business_signals IS
    'IR/DART and document parser qualitative business signals. Source of truth for reusable signals.';
