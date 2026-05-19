-- V28: consolidate raw article source metadata and formalize stock OHLCV.
--
-- Safety policy:
--   * raw_articles rows are never deleted or rewritten except existing metadata
--     compatibility reads.
--   * source metadata is copied into raw_article_source_metadata before the old
--     per-source side tables are dropped.
--   * market_price_ohlcv rows are preserved; instrument_id is added as a
--     normalized lookup while legacy peer_id/ticker columns remain.

CREATE TABLE IF NOT EXISTS raw_article_source_metadata (
    raw_article_id BIGINT PRIMARY KEY
        REFERENCES raw_articles(id) ON DELETE CASCADE,
    source_type VARCHAR(50) NOT NULL,
    source_name VARCHAR(100),
    source_metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    external_id TEXT GENERATED ALWAYS AS (
        COALESCE(
            source_metadata ->> 'rcept_no',
            source_metadata ->> 'receipt_no',
            source_metadata ->> 'emp_seqno',
            source_metadata ->> 'content_hash',
            source_metadata ->> 'item_code'
        )
    ) STORED,
    period TEXT GENERATED ALWAYS AS (source_metadata ->> 'period') STORED,
    document_url TEXT GENERATED ALWAYS AS (
        COALESCE(
            source_metadata ->> 'pdf_url',
            source_metadata ->> 'detail_url',
            source_metadata ->> 'source_url',
            source_metadata ->> 'list_url'
        )
    ) STORED,
    company_name TEXT GENERATED ALWAYS AS (
        COALESCE(
            source_metadata ->> 'company_name',
            source_metadata ->> 'corp_name',
            source_metadata ->> 'company'
        )
    ) STORED,
    parser_quality_label TEXT GENERATED ALWAYS AS (
        source_metadata ->> 'parser_quality_label'
    ) STORED,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_raw_article_source_metadata_type_name
    ON raw_article_source_metadata (source_type, source_name);
CREATE INDEX IF NOT EXISTS idx_raw_article_source_metadata_payload_gin
    ON raw_article_source_metadata USING GIN(source_metadata);
CREATE INDEX IF NOT EXISTS idx_raw_article_source_metadata_external_id
    ON raw_article_source_metadata (external_id)
    WHERE external_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_raw_article_source_metadata_period
    ON raw_article_source_metadata (period)
    WHERE period IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_raw_article_source_metadata_document_url
    ON raw_article_source_metadata (document_url)
    WHERE document_url IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_raw_article_source_metadata_company_name
    ON raw_article_source_metadata (company_name)
    WHERE company_name IS NOT NULL;

WITH source_rows AS (
    SELECT 'news'::varchar(50) AS source_type, raw_article_id, source_metadata
    FROM raw_article_metadata_news
    UNION ALL
    SELECT 'official'::varchar(50), raw_article_id, source_metadata
    FROM raw_article_metadata_official
    UNION ALL
    SELECT 'company_site'::varchar(50), raw_article_id, source_metadata
    FROM raw_article_metadata_company_site
    UNION ALL
    SELECT 'dart'::varchar(50), raw_article_id, source_metadata
    FROM raw_article_metadata_dart
    UNION ALL
    SELECT 'ir'::varchar(50), raw_article_id, source_metadata
    FROM raw_article_metadata_ir
    UNION ALL
    SELECT 'securities_report'::varchar(50), raw_article_id, source_metadata
    FROM raw_article_metadata_securities_report
    UNION ALL
    SELECT 'trend_report'::varchar(50), raw_article_id, source_metadata
    FROM raw_article_metadata_trend_report
    UNION ALL
    SELECT 'search_trend'::varchar(50), raw_article_id, source_metadata
    FROM raw_article_metadata_search_trend
    UNION ALL
    SELECT 'job'::varchar(50), raw_article_id, source_metadata
    FROM raw_article_metadata_job
    UNION ALL
    SELECT 'market_data'::varchar(50), raw_article_id, source_metadata
    FROM raw_article_metadata_market_data
    UNION ALL
    SELECT 'social'::varchar(50), raw_article_id, source_metadata
    FROM raw_article_metadata_social
)
INSERT INTO raw_article_source_metadata (
    raw_article_id,
    source_type,
    source_name,
    source_metadata
)
SELECT
    ra.id,
    ra.source_type,
    ra.source_name,
    COALESCE(sr.source_metadata, axis_raw_article_source_metadata(ra.metadata), '{}'::jsonb)
FROM raw_articles ra
LEFT JOIN source_rows sr
    ON sr.raw_article_id = ra.id
   AND sr.source_type = ra.source_type
ON CONFLICT (raw_article_id) DO UPDATE SET
    source_type = EXCLUDED.source_type,
    source_name = COALESCE(EXCLUDED.source_name, raw_article_source_metadata.source_name),
    source_metadata = CASE
        WHEN EXCLUDED.source_metadata = '{}'::jsonb THEN
            raw_article_source_metadata.source_metadata
        ELSE
            raw_article_source_metadata.source_metadata || EXCLUDED.source_metadata
    END,
    updated_at = NOW();

DO $$
DECLARE
    missing_count INT;
    mismatch_count INT;
BEGIN
    SELECT COUNT(*) INTO missing_count
    FROM raw_articles ra
    LEFT JOIN raw_article_source_metadata sm
        ON sm.raw_article_id = ra.id
    WHERE sm.raw_article_id IS NULL;

    IF missing_count > 0 THEN
        RAISE EXCEPTION
            'V28 aborted: raw_article_source_metadata missing rows: %',
            missing_count;
    END IF;

    WITH source_rows AS (
        SELECT raw_article_id, source_metadata FROM raw_article_metadata_news
        UNION ALL SELECT raw_article_id, source_metadata FROM raw_article_metadata_official
        UNION ALL SELECT raw_article_id, source_metadata FROM raw_article_metadata_company_site
        UNION ALL SELECT raw_article_id, source_metadata FROM raw_article_metadata_dart
        UNION ALL SELECT raw_article_id, source_metadata FROM raw_article_metadata_ir
        UNION ALL SELECT raw_article_id, source_metadata FROM raw_article_metadata_securities_report
        UNION ALL SELECT raw_article_id, source_metadata FROM raw_article_metadata_trend_report
        UNION ALL SELECT raw_article_id, source_metadata FROM raw_article_metadata_search_trend
        UNION ALL SELECT raw_article_id, source_metadata FROM raw_article_metadata_job
        UNION ALL SELECT raw_article_id, source_metadata FROM raw_article_metadata_market_data
        UNION ALL SELECT raw_article_id, source_metadata FROM raw_article_metadata_social
    )
    SELECT COUNT(*) INTO mismatch_count
    FROM source_rows sr
    JOIN raw_article_source_metadata sm
        ON sm.raw_article_id = sr.raw_article_id
    WHERE NOT (sm.source_metadata @> sr.source_metadata);

    IF mismatch_count > 0 THEN
        RAISE EXCEPTION
            'V28 aborted: source metadata copy mismatch rows: %',
            mismatch_count;
    END IF;
END $$;

CREATE OR REPLACE VIEW raw_article_metadata_unified AS
SELECT
    ra.id AS raw_article_id,
    ra.source_type,
    ra.source_name,
    ra.metadata AS common_metadata,
    COALESCE(sm.source_metadata, '{}'::jsonb) AS source_metadata,
    ra.metadata || COALESCE(sm.source_metadata, '{}'::jsonb) AS metadata
FROM raw_articles ra
LEFT JOIN raw_article_source_metadata sm
    ON sm.raw_article_id = ra.id;

DROP TRIGGER IF EXISTS trg_raw_article_source_metadata_touch
    ON raw_article_source_metadata;
CREATE TRIGGER trg_raw_article_source_metadata_touch
BEFORE UPDATE ON raw_article_source_metadata
FOR EACH ROW
EXECUTE FUNCTION axis_touch_updated_at();

DROP TRIGGER IF EXISTS trg_raw_article_source_metadata_refresh_normalized
    ON raw_article_source_metadata;
CREATE TRIGGER trg_raw_article_source_metadata_refresh_normalized
AFTER INSERT OR UPDATE OF source_metadata ON raw_article_source_metadata
FOR EACH ROW
EXECUTE FUNCTION axis_refresh_raw_article_normalized_metadata_trigger();

DO $$
DECLARE
    article RECORD;
BEGIN
    FOR article IN
        SELECT raw_article_id
        FROM raw_article_source_metadata
        WHERE jsonb_typeof(source_metadata -> 'parser_result') = 'object'
    LOOP
        PERFORM axis_refresh_raw_article_normalized_metadata(article.raw_article_id);
    END LOOP;
END $$;

DROP TABLE IF EXISTS raw_article_metadata_news;
DROP TABLE IF EXISTS raw_article_metadata_official;
DROP TABLE IF EXISTS raw_article_metadata_company_site;
DROP TABLE IF EXISTS raw_article_metadata_dart;
DROP TABLE IF EXISTS raw_article_metadata_ir;
DROP TABLE IF EXISTS raw_article_metadata_securities_report;
DROP TABLE IF EXISTS raw_article_metadata_trend_report;
DROP TABLE IF EXISTS raw_article_metadata_search_trend;
DROP TABLE IF EXISTS raw_article_metadata_job;
DROP TABLE IF EXISTS raw_article_metadata_market_data;
DROP TABLE IF EXISTS raw_article_metadata_social;

CREATE OR REPLACE FUNCTION axis_source_credibility_score(input_source_type TEXT)
RETURNS DOUBLE PRECISION
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT CASE LOWER(COALESCE(input_source_type, ''))
        WHEN 'dart' THEN 1.00
        WHEN 'ir' THEN 1.00
        WHEN 'official' THEN 0.90
        WHEN 'company_site' THEN 0.90
        WHEN 'securities_report' THEN 0.80
        WHEN 'trend_report' THEN 0.70
        WHEN 'news' THEN 0.70
        WHEN 'market_data' THEN 0.70
        WHEN 'job' THEN 0.60
        WHEN 'search_trend' THEN 0.55
        WHEN 'social' THEN 0.40
        ELSE 0.50
    END;
$$;

CREATE OR REPLACE FUNCTION axis_source_credibility_grade(input_score DOUBLE PRECISION)
RETURNS TEXT
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT CASE
        WHEN COALESCE(input_score, 0) >= 0.85 THEN 'High'
        WHEN COALESCE(input_score, 0) >= 0.60 THEN 'Medium'
        WHEN COALESCE(input_score, 0) >= 0.40 THEN 'Low'
        ELSE 'Unverified'
    END;
$$;

CREATE TABLE IF NOT EXISTS market_price_ohlcv (
    id BIGSERIAL PRIMARY KEY,
    raw_article_id BIGINT REFERENCES raw_articles(id) ON DELETE SET NULL,
    peer_id TEXT,
    ticker TEXT NOT NULL,
    trade_date DATE NOT NULL,
    open NUMERIC,
    high NUMERIC,
    low NUMERIC,
    close NUMERIC,
    volume BIGINT,
    change_pct NUMERIC,
    currency TEXT DEFAULT 'KRW',
    source_type TEXT NOT NULL DEFAULT 'market_data',
    source_name TEXT,
    publisher TEXT,
    collected_at TIMESTAMPTZ,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT market_price_ohlcv_ticker_trade_date_source_name_key
        UNIQUE (ticker, trade_date, source_name)
);

ALTER TABLE market_price_ohlcv
    ADD COLUMN IF NOT EXISTS instrument_id BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'market_price_ohlcv_ticker_trade_date_source_name_key'
          AND conrelid = 'market_price_ohlcv'::regclass
    ) THEN
        ALTER TABLE market_price_ohlcv
            ADD CONSTRAINT market_price_ohlcv_ticker_trade_date_source_name_key
            UNIQUE (ticker, trade_date, source_name);
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'market_price_ohlcv_raw_article_id_fkey'
          AND conrelid = 'market_price_ohlcv'::regclass
    ) THEN
        ALTER TABLE market_price_ohlcv
            ADD CONSTRAINT market_price_ohlcv_raw_article_id_fkey
            FOREIGN KEY (raw_article_id)
            REFERENCES raw_articles(id)
            ON DELETE SET NULL;
    END IF;
END $$;

WITH market_rows AS (
    SELECT
        ra.id AS raw_article_id,
        ra.company ->> 0 AS peer_id,
        COALESCE(
            sm.source_metadata #>> '{validation,resolved_symbol}',
            sm.source_metadata #>> '{validation,requested_ticker}',
            sm.source_metadata #>> '{latest_price,ticker}',
            sm.source_metadata #>> '{realtime_quote,ticker}'
        ) AS ticker,
        COALESCE(item ->> 'trade_date', item ->> 'date')::date AS trade_date,
        NULLIF(item ->> 'open', '')::numeric AS open,
        NULLIF(item ->> 'high', '')::numeric AS high,
        NULLIF(item ->> 'low', '')::numeric AS low,
        NULLIF(item ->> 'close', '')::numeric AS close,
        NULLIF(item ->> 'volume', '')::bigint AS volume,
        NULLIF(item ->> 'change_pct', '')::numeric AS change_pct,
        COALESCE(sm.source_metadata #>> '{target,currency}', 'KRW') AS currency,
        ra.source_type,
        COALESCE(ra.source_name, 'stock_market') AS source_name,
        ra.publisher,
        ra.collected_at,
        item AS payload
    FROM raw_articles ra
    JOIN raw_article_source_metadata sm
        ON sm.raw_article_id = ra.id
    CROSS JOIN LATERAL jsonb_array_elements(
        CASE
            WHEN jsonb_typeof(sm.source_metadata -> 'data') = 'array'
                THEN sm.source_metadata -> 'data'
            ELSE '[]'::jsonb
        END
    ) AS item
    WHERE ra.source_type = 'market_data'
),
deduped_market_rows AS (
    SELECT *
    FROM (
        SELECT
            market_rows.*,
            ROW_NUMBER() OVER (
                PARTITION BY ticker, trade_date, source_name
                ORDER BY collected_at DESC NULLS LAST, raw_article_id DESC
            ) AS row_rank
        FROM market_rows
        WHERE ticker IS NOT NULL
          AND trade_date IS NOT NULL
    ) ranked
    WHERE row_rank = 1
)
INSERT INTO market_price_ohlcv (
    raw_article_id,
    peer_id,
    ticker,
    trade_date,
    open,
    high,
    low,
    close,
    volume,
    change_pct,
    currency,
    source_type,
    source_name,
    publisher,
    collected_at,
    payload
)
SELECT
    raw_article_id,
    peer_id,
    ticker,
    trade_date,
    open,
    high,
    low,
    close,
    volume,
    change_pct,
    currency,
    source_type,
    source_name,
    publisher,
    collected_at,
    payload
FROM deduped_market_rows
ON CONFLICT (ticker, trade_date, source_name) DO UPDATE SET
    raw_article_id = COALESCE(EXCLUDED.raw_article_id, market_price_ohlcv.raw_article_id),
    peer_id = COALESCE(EXCLUDED.peer_id, market_price_ohlcv.peer_id),
    open = EXCLUDED.open,
    high = EXCLUDED.high,
    low = EXCLUDED.low,
    close = EXCLUDED.close,
    volume = EXCLUDED.volume,
    change_pct = EXCLUDED.change_pct,
    currency = COALESCE(EXCLUDED.currency, market_price_ohlcv.currency),
    source_type = EXCLUDED.source_type,
    publisher = COALESCE(EXCLUDED.publisher, market_price_ohlcv.publisher),
    collected_at = COALESCE(EXCLUDED.collected_at, market_price_ohlcv.collected_at),
    payload = market_price_ohlcv.payload || EXCLUDED.payload,
    updated_at = NOW();

CREATE TABLE IF NOT EXISTS market_instruments (
    id BIGSERIAL PRIMARY KEY,
    peer_company_id VARCHAR(50)
        REFERENCES peer_companies(id) ON DELETE RESTRICT,
    ticker VARCHAR(20) NOT NULL,
    exchange VARCHAR(20) NOT NULL DEFAULT 'KRX',
    currency VARCHAR(10) NOT NULL DEFAULT 'KRW',
    instrument_name VARCHAR(100),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_market_instruments_exchange_ticker
        UNIQUE (exchange, ticker)
);

INSERT INTO market_instruments (
    peer_company_id,
    ticker,
    exchange,
    currency,
    instrument_name
)
SELECT
    pc.id,
    mp.ticker,
    'KRX',
    COALESCE(MAX(mp.currency), 'KRW'),
    MAX(pc.name)
FROM market_price_ohlcv mp
LEFT JOIN peer_companies pc
    ON pc.id = mp.peer_id
WHERE mp.ticker IS NOT NULL
GROUP BY pc.id, mp.ticker
ON CONFLICT (exchange, ticker) DO UPDATE SET
    peer_company_id = COALESCE(EXCLUDED.peer_company_id, market_instruments.peer_company_id),
    currency = COALESCE(EXCLUDED.currency, market_instruments.currency),
    instrument_name = COALESCE(EXCLUDED.instrument_name, market_instruments.instrument_name),
    updated_at = NOW();

UPDATE market_price_ohlcv mp
SET instrument_id = mi.id
FROM market_instruments mi
WHERE mp.instrument_id IS NULL
  AND mi.exchange = 'KRX'
  AND mi.ticker = mp.ticker;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_market_price_ohlcv_instrument'
          AND conrelid = 'market_price_ohlcv'::regclass
    ) THEN
        ALTER TABLE market_price_ohlcv
            ADD CONSTRAINT fk_market_price_ohlcv_instrument
            FOREIGN KEY (instrument_id)
            REFERENCES market_instruments(id)
            ON DELETE RESTRICT;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_market_instruments_peer_company
    ON market_instruments (peer_company_id)
    WHERE peer_company_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_market_price_ohlcv_peer_date
    ON market_price_ohlcv (peer_id, trade_date DESC);
CREATE INDEX IF NOT EXISTS idx_market_price_ohlcv_instrument_date
    ON market_price_ohlcv (instrument_id, trade_date DESC)
    WHERE instrument_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_market_price_ohlcv_raw_article
    ON market_price_ohlcv (raw_article_id)
    WHERE raw_article_id IS NOT NULL;

DROP TRIGGER IF EXISTS trg_market_instruments_touch
    ON market_instruments;
CREATE TRIGGER trg_market_instruments_touch
BEFORE UPDATE ON market_instruments
FOR EACH ROW
EXECUTE FUNCTION axis_touch_updated_at();

DROP TRIGGER IF EXISTS trg_market_price_ohlcv_touch
    ON market_price_ohlcv;
CREATE TRIGGER trg_market_price_ohlcv_touch
BEFORE UPDATE ON market_price_ohlcv
FOR EACH ROW
EXECUTE FUNCTION axis_touch_updated_at();

COMMENT ON TABLE raw_article_source_metadata IS
    'Canonical one-to-one source payload sidecar for raw_articles; replaces raw_article_metadata_* tables.';
COMMENT ON VIEW raw_article_metadata_unified IS
    'Compatibility view exposing raw_articles.metadata merged with raw_article_source_metadata.source_metadata.';
COMMENT ON TABLE market_instruments IS
    'Normalized stock/market instrument master used by market_price_ohlcv.';
COMMENT ON TABLE market_price_ohlcv IS
    'Daily OHLCV stock market data collected as structured market_data signals.';
