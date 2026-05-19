-- V29: front-facing product read models and analysis tables.
--
-- Safety policy:
--   * This migration is additive-first. It does not delete raw_articles,
--     source metadata, card_news, peer_companies, or OHLCV rows.
--   * market_instruments is not dropped in this step. Its useful values are
--     copied onto market_price_ohlcv so a later migration can remove the lookup
--     only after application code and data checks are complete.
--   * Row counts for the data-bearing tables touched here are validated at the
--     end of the migration.

DROP TABLE IF EXISTS axis_v29_pre_counts;

CREATE TEMP TABLE axis_v29_pre_counts ON COMMIT DROP AS
SELECT 'raw_articles'::text AS table_name, COUNT(*)::bigint AS row_count FROM raw_articles
UNION ALL
SELECT 'raw_article_source_metadata', COUNT(*)::bigint FROM raw_article_source_metadata
UNION ALL
SELECT 'card_news', COUNT(*)::bigint FROM card_news
UNION ALL
SELECT 'peer_companies', COUNT(*)::bigint FROM peer_companies
UNION ALL
SELECT 'market_price_ohlcv', COUNT(*)::bigint FROM market_price_ohlcv;

-- ============================================================
-- 1. Peer+ front read model columns
-- ============================================================

ALTER TABLE peer_companies
    ADD COLUMN IF NOT EXISTS dart_period TEXT,
    ADD COLUMN IF NOT EXISTS dart_revenue_krwbn NUMERIC(18,2),
    ADD COLUMN IF NOT EXISTS dart_operating_profit_krwbn NUMERIC(18,2),
    ADD COLUMN IF NOT EXISTS dart_operating_margin_pct NUMERIC(9,4),
    ADD COLUMN IF NOT EXISTS dart_revenue_growth_pct NUMERIC(9,4),
    ADD COLUMN IF NOT EXISTS dart_operating_profit_growth_pct NUMERIC(9,4),
    ADD COLUMN IF NOT EXISTS ax_revenue_share_pct NUMERIC(9,4),
    ADD COLUMN IF NOT EXISTS contract_count INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS core_keywords TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN IF NOT EXISTS peer_plus_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS financial_updated_at TIMESTAMPTZ;

UPDATE peer_companies
SET core_keywords = CASE
        WHEN core_keywords IS NULL OR cardinality(core_keywords) = 0 THEN COALESCE(keywords, '{}')
        ELSE core_keywords
    END;

WITH latest_financials AS (
    SELECT *
    FROM (
        SELECT
            pf.*,
            LEAD(pf.revenue_total_krwbn) OVER (
                PARTITION BY pf.peer_id
                ORDER BY pf.report_date DESC NULLS LAST, pf.period DESC NULLS LAST, pf.created_at DESC NULLS LAST
            ) AS prev_revenue_total_krwbn,
            LEAD(pf.operating_profit_krwbn) OVER (
                PARTITION BY pf.peer_id
                ORDER BY pf.report_date DESC NULLS LAST, pf.period DESC NULLS LAST, pf.created_at DESC NULLS LAST
            ) AS prev_operating_profit_krwbn,
            ROW_NUMBER() OVER (
                PARTITION BY pf.peer_id
                ORDER BY pf.report_date DESC NULLS LAST, pf.period DESC NULLS LAST, pf.created_at DESC NULLS LAST
            ) AS row_rank
        FROM peer_financials pf
    ) ranked
    WHERE row_rank = 1
)
UPDATE peer_companies pc
SET
    dart_period = COALESCE(pc.dart_period, latest_financials.period),
    dart_revenue_krwbn = COALESCE(pc.dart_revenue_krwbn, latest_financials.revenue_total_krwbn::numeric),
    dart_operating_profit_krwbn = COALESCE(pc.dart_operating_profit_krwbn, latest_financials.operating_profit_krwbn::numeric),
    dart_operating_margin_pct = COALESCE(
        pc.dart_operating_margin_pct,
        CASE
            WHEN latest_financials.revenue_total_krwbn IS NOT NULL
             AND latest_financials.revenue_total_krwbn <> 0
            THEN ROUND((latest_financials.operating_profit_krwbn / latest_financials.revenue_total_krwbn * 100)::numeric, 4)
            ELSE NULL
        END
    ),
    dart_revenue_growth_pct = COALESCE(
        pc.dart_revenue_growth_pct,
        CASE
            WHEN latest_financials.prev_revenue_total_krwbn IS NOT NULL
             AND latest_financials.prev_revenue_total_krwbn <> 0
            THEN ROUND(
                ((latest_financials.revenue_total_krwbn - latest_financials.prev_revenue_total_krwbn)
                    / ABS(latest_financials.prev_revenue_total_krwbn) * 100)::numeric,
                4
            )
            ELSE NULL
        END
    ),
    dart_operating_profit_growth_pct = COALESCE(
        pc.dart_operating_profit_growth_pct,
        CASE
            WHEN latest_financials.prev_operating_profit_krwbn IS NOT NULL
             AND latest_financials.prev_operating_profit_krwbn <> 0
            THEN ROUND(
                ((latest_financials.operating_profit_krwbn - latest_financials.prev_operating_profit_krwbn)
                    / ABS(latest_financials.prev_operating_profit_krwbn) * 100)::numeric,
                4
            )
            ELSE NULL
        END
    ),
    ax_revenue_share_pct = COALESCE(pc.ax_revenue_share_pct, latest_financials.ai_revenue_share_pct::numeric),
    peer_plus_payload = CASE
        WHEN pc.peer_plus_payload = '{}'::jsonb THEN
            jsonb_strip_nulls(jsonb_build_object(
                'source', latest_financials.source,
                'report_date', latest_financials.report_date,
                'dart_rcept_no', latest_financials.dart_rcept_no,
                'ir_page', latest_financials.ir_page,
                'segment_revenue', latest_financials.segment_revenue,
                'headcount', latest_financials.headcount
            ))
        ELSE pc.peer_plus_payload
    END,
    financial_updated_at = COALESCE(pc.financial_updated_at, NOW())
FROM latest_financials
WHERE latest_financials.peer_id = pc.id;

WITH contract_counts AS (
    SELECT
        COALESCE(cn.peer_company_id, cn.company) AS peer_company_id,
        COUNT(*)::int AS contract_count
    FROM card_news cn
    WHERE cn.event_type = 'contract'
       OR cn.title ILIKE '%수주%'
       OR cn.implication ->> 'event_type' = 'contract'
    GROUP BY COALESCE(cn.peer_company_id, cn.company)
)
UPDATE peer_companies pc
SET contract_count = GREATEST(COALESCE(pc.contract_count, 0), contract_counts.contract_count)
FROM contract_counts
WHERE contract_counts.peer_company_id = pc.id;

CREATE INDEX IF NOT EXISTS idx_peer_companies_core_keywords
    ON peer_companies USING GIN(core_keywords);

-- ============================================================
-- 2. Card keyword read model columns for keyword graph generation
-- ============================================================

ALTER TABLE card_news
    ADD COLUMN IF NOT EXISTS primary_keyword_category VARCHAR(80),
    ADD COLUMN IF NOT EXISTS keyword_categories JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS keywords TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN IF NOT EXISTS keyword_frequency JSONB NOT NULL DEFAULT '{}'::jsonb;

WITH derived_card_keywords AS (
    SELECT
        cn.id,
        COALESCE(NULLIF(cn.implication ->> 'sector', ''), NULLIF(cn.event_type, ''), 'other') AS primary_category,
        CASE
            WHEN jsonb_typeof(cn.implication -> 'sectors') = 'array'
             AND jsonb_array_length(cn.implication -> 'sectors') > 0
            THEN (
                SELECT COALESCE(jsonb_agg(DISTINCT sector_value), '[]'::jsonb)
                FROM jsonb_array_elements_text(cn.implication -> 'sectors') AS sectors(sector_value)
                WHERE sector_value <> ''
            )
            ELSE jsonb_build_array(COALESCE(NULLIF(cn.implication ->> 'sector', ''), NULLIF(cn.event_type, ''), 'other'))
        END AS categories,
        CASE
            WHEN jsonb_typeof(cn.implication -> 'keywords') = 'array'
             AND jsonb_array_length(cn.implication -> 'keywords') > 0
            THEN ARRAY(
                SELECT DISTINCT keyword_value
                FROM jsonb_array_elements_text(cn.implication -> 'keywords') AS keywords(keyword_value)
                WHERE keyword_value <> ''
            )
            ELSE ARRAY_REMOVE(ARRAY[
                COALESCE(NULLIF(cn.implication ->> 'sector', ''), NULLIF(cn.event_type, ''), 'other'),
                NULLIF(cn.event_type, ''),
                NULLIF(cn.company, '')
            ], NULL)
        END AS keyword_values
    FROM card_news cn
)
UPDATE card_news cn
SET
    primary_keyword_category = COALESCE(cn.primary_keyword_category, derived.primary_category),
    keyword_categories = CASE
        WHEN cn.keyword_categories IS NULL OR cn.keyword_categories = '[]'::jsonb THEN derived.categories
        ELSE cn.keyword_categories
    END,
    keywords = CASE
        WHEN cn.keywords IS NULL OR cardinality(cn.keywords) = 0 THEN derived.keyword_values
        ELSE cn.keywords
    END,
    keyword_frequency = CASE
        WHEN cn.keyword_frequency IS NULL OR cn.keyword_frequency = '{}'::jsonb THEN (
            SELECT COALESCE(jsonb_object_agg(keyword, frequency), '{}'::jsonb)
            FROM (
                SELECT keyword, COUNT(*)::int AS frequency
                FROM unnest(derived.keyword_values) AS keyword_items(keyword)
                WHERE keyword <> ''
                GROUP BY keyword
            ) frequencies
        )
        ELSE cn.keyword_frequency
    END
FROM derived_card_keywords derived
WHERE derived.id = cn.id;

CREATE INDEX IF NOT EXISTS idx_card_news_primary_keyword_category
    ON card_news (primary_keyword_category);
CREATE INDEX IF NOT EXISTS idx_card_news_keywords
    ON card_news USING GIN(keywords);
CREATE INDEX IF NOT EXISTS idx_card_news_keyword_categories
    ON card_news USING GIN(keyword_categories);

-- ============================================================
-- 3. De-normalized OHLCV fields to make market_instruments removable later
-- ============================================================

ALTER TABLE market_price_ohlcv
    ADD COLUMN IF NOT EXISTS peer_company_id VARCHAR(50),
    ADD COLUMN IF NOT EXISTS exchange VARCHAR(20) NOT NULL DEFAULT 'KRX',
    ADD COLUMN IF NOT EXISTS instrument_name VARCHAR(100);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_market_price_ohlcv_peer_company'
          AND conrelid = 'market_price_ohlcv'::regclass
    ) THEN
        ALTER TABLE market_price_ohlcv
            ADD CONSTRAINT fk_market_price_ohlcv_peer_company
            FOREIGN KEY (peer_company_id)
            REFERENCES peer_companies(id)
            ON DELETE SET NULL;
    END IF;
END $$;

UPDATE market_price_ohlcv mp
SET
    peer_company_id = COALESCE(mp.peer_company_id, mi.peer_company_id, mp.peer_id),
    exchange = COALESCE(mp.exchange, mi.exchange, 'KRX'),
    instrument_name = COALESCE(mp.instrument_name, mi.instrument_name, pc.name)
FROM market_instruments mi
LEFT JOIN peer_companies pc
    ON pc.id = mi.peer_company_id
WHERE mi.id = mp.instrument_id;

UPDATE market_price_ohlcv mp
SET
    peer_company_id = COALESCE(mp.peer_company_id, mp.peer_id),
    instrument_name = COALESCE(mp.instrument_name, pc.name)
FROM peer_companies pc
WHERE pc.id = mp.peer_id;

CREATE INDEX IF NOT EXISTS idx_market_price_ohlcv_peer_company_date
    ON market_price_ohlcv (peer_company_id, trade_date DESC)
    WHERE peer_company_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_market_price_ohlcv_exchange_ticker_date
    ON market_price_ohlcv (exchange, ticker, trade_date DESC);

-- ============================================================
-- 4. Front-facing analysis domain tables
-- ============================================================

CREATE TABLE IF NOT EXISTS mixer_results (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    title VARCHAR(300),
    requested_by_user_id BIGINT,
    input_card_ids TEXT[] NOT NULL DEFAULT '{}',
    input_peer_ids TEXT[] NOT NULL DEFAULT '{}',
    input_keywords TEXT[] NOT NULL DEFAULT '{}',
    ratios JSONB NOT NULL DEFAULT '{}'::jsonb,
    generated_implication JSONB NOT NULL DEFAULT '{}'::jsonb,
    insight_brief JSONB NOT NULL DEFAULT '[]'::jsonb,
    radar_axes JSONB NOT NULL DEFAULT '[]'::jsonb,
    connections JSONB NOT NULL DEFAULT '[]'::jsonb,
    sk_ax_implication TEXT,
    final_one_liner TEXT,
    confidence NUMERIC(3,2),
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_mixer_results_created_at
    ON mixer_results (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_mixer_results_input_cards
    ON mixer_results USING GIN(input_card_ids);
CREATE INDEX IF NOT EXISTS idx_mixer_results_input_keywords
    ON mixer_results USING GIN(input_keywords);

CREATE TABLE IF NOT EXISTS insight_reports (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    title VARCHAR(300),
    insight_type VARCHAR(40) NOT NULL DEFAULT 'cascade',
    status VARCHAR(30) NOT NULL DEFAULT 'completed',
    requested_by_user_id BIGINT,
    focus_peer_ids TEXT[] NOT NULL DEFAULT '{}',
    focus_card_ids TEXT[] NOT NULL DEFAULT '{}',
    focus_keywords TEXT[] NOT NULL DEFAULT '{}',
    date_from DATE,
    date_to DATE,
    summary TEXT,
    final_one_liner TEXT,
    sk_ax_implication TEXT,
    reasoning_steps JSONB NOT NULL DEFAULT '[]'::jsonb,
    evidence JSONB NOT NULL DEFAULT '[]'::jsonb,
    source_card_ids TEXT[] NOT NULL DEFAULT '{}',
    confidence NUMERIC(3,2),
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_insight_reports_created_at
    ON insight_reports (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_insight_reports_focus_peers
    ON insight_reports USING GIN(focus_peer_ids);
CREATE INDEX IF NOT EXISTS idx_insight_reports_source_cards
    ON insight_reports USING GIN(source_card_ids);

CREATE TABLE IF NOT EXISTS global_industry_trends (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    trend_date DATE NOT NULL DEFAULT CURRENT_DATE,
    industry VARCHAR(100) NOT NULL,
    region VARCHAR(50) NOT NULL DEFAULT 'global',
    keyword VARCHAR(120) NOT NULL,
    keyword_category VARCHAR(80),
    title VARCHAR(300),
    summary TEXT,
    mention_count INT NOT NULL DEFAULT 0,
    impact_score NUMERIC(5,2),
    confidence NUMERIC(3,2),
    related_peer_ids TEXT[] NOT NULL DEFAULT '{}',
    related_card_ids TEXT[] NOT NULL DEFAULT '{}',
    source_raw_article_ids BIGINT[] NOT NULL DEFAULT '{}',
    sk_ax_implication TEXT,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_global_industry_trends_daily_keyword
        UNIQUE (trend_date, industry, region, keyword)
);

CREATE INDEX IF NOT EXISTS idx_global_industry_trends_date
    ON global_industry_trends (trend_date DESC);
CREATE INDEX IF NOT EXISTS idx_global_industry_trends_keyword
    ON global_industry_trends (keyword);
CREATE INDEX IF NOT EXISTS idx_global_industry_trends_related_cards
    ON global_industry_trends USING GIN(related_card_ids);

DROP TRIGGER IF EXISTS trg_mixer_results_touch ON mixer_results;
CREATE TRIGGER trg_mixer_results_touch
BEFORE UPDATE ON mixer_results
FOR EACH ROW
EXECUTE FUNCTION axis_touch_updated_at();

DROP TRIGGER IF EXISTS trg_insight_reports_touch ON insight_reports;
CREATE TRIGGER trg_insight_reports_touch
BEFORE UPDATE ON insight_reports
FOR EACH ROW
EXECUTE FUNCTION axis_touch_updated_at();

DROP TRIGGER IF EXISTS trg_global_industry_trends_touch ON global_industry_trends;
CREATE TRIGGER trg_global_industry_trends_touch
BEFORE UPDATE ON global_industry_trends
FOR EACH ROW
EXECUTE FUNCTION axis_touch_updated_at();

-- ============================================================
-- 5. Data preservation checks
-- ============================================================

DO $$
DECLARE
    changed_count INT;
BEGIN
    WITH current_counts AS (
        SELECT 'raw_articles'::text AS table_name, COUNT(*)::bigint AS row_count FROM raw_articles
        UNION ALL
        SELECT 'raw_article_source_metadata', COUNT(*)::bigint FROM raw_article_source_metadata
        UNION ALL
        SELECT 'card_news', COUNT(*)::bigint FROM card_news
        UNION ALL
        SELECT 'peer_companies', COUNT(*)::bigint FROM peer_companies
        UNION ALL
        SELECT 'market_price_ohlcv', COUNT(*)::bigint FROM market_price_ohlcv
    )
    SELECT COUNT(*) INTO changed_count
    FROM axis_v29_pre_counts before_counts
    JOIN current_counts after_counts
        ON after_counts.table_name = before_counts.table_name
    WHERE after_counts.row_count <> before_counts.row_count;

    IF changed_count > 0 THEN
        RAISE EXCEPTION 'V29 aborted: protected table row count changed';
    END IF;
END $$;

COMMENT ON COLUMN card_news.primary_keyword_category IS
    'Front keyword graph primary category. Backfilled from implication.sector/event_type.';
COMMENT ON COLUMN card_news.keyword_categories IS
    'Keyword graph categories for this card. Prefer implication.sectors when present.';
COMMENT ON COLUMN card_news.keywords IS
    'Flat keywords used by backend keyword frequency/graph generation.';
COMMENT ON COLUMN card_news.keyword_frequency IS
    'Per-card keyword frequency map for graph pre-processing.';
COMMENT ON TABLE mixer_results IS
    'Front-facing Mixer analysis result read model. Stores selected cards and generated implications without relying on analysis_ledger.';
COMMENT ON TABLE insight_reports IS
    'Front-facing insight report read model for insight cascade outputs.';
COMMENT ON TABLE global_industry_trends IS
    'Global industry trend read model for keyword/industry map and SK AX implication.';
COMMENT ON COLUMN market_price_ohlcv.peer_company_id IS
    'Denormalized peer id copied from market_instruments/legacy peer_id so market_instruments can be removed later.';
COMMENT ON TABLE market_instruments IS
    'Deprecated after V29: useful fields are copied to market_price_ohlcv; keep until post-deploy data checks allow drop.';
