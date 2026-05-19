-- V30: collapse legacy support tables into the minimal product schema.
--
-- Safety policy:
--   * Do not delete business data. Every row from a dropped table is copied to
--     legacy_records before the original table is dropped.
--   * Data with a clear owner is also folded into the surviving product tables
--     as JSONB/array read-model fields.
--   * Protected core table row counts are validated before the migration ends.

DROP TABLE IF EXISTS axis_v30_tables_to_drop;
DROP TABLE IF EXISTS axis_v30_pre_drop_counts;
DROP TABLE IF EXISTS axis_v30_pre_core_counts;

CREATE TEMP TABLE axis_v30_tables_to_drop (
    source_table TEXT PRIMARY KEY,
    pk_expr TEXT NOT NULL
) ON COMMIT DROP;

INSERT INTO axis_v30_tables_to_drop (source_table, pk_expr) VALUES
    ('analysis_ledger', 'id::text'),
    ('analysis_ledger_card_news', 'id::text'),
    ('analysis_ledger_peer_companies', 'id::text'),
    ('article_images', 'id::text'),
    ('article_peer_companies', 'id::text'),
    ('audit_logs', 'id::text'),
    ('briefing_history', 'id::text'),
    ('briefing_history_cards', 'id::text'),
    ('briefing_recipients', 'id::text'),
    ('briefing_report_articles', 'id::text'),
    ('briefing_report_cards', 'id::text'),
    ('card_news_articles', 'id::text'),
    ('chat_sessions', 'id::text'),
    ('chat_turns', 'id::text'),
    ('cost_daily_billed', 'id::text'),
    ('crawl_logs', 'id::text'),
    ('crawl_run_articles', 'id::text'),
    ('crawl_runs', 'id::text'),
    ('evidence_chain', 'COALESCE(card_news_id, issue_card_id)'),
    ('feedback', 'id::text'),
    ('golden_set', 'id::text'),
    ('infra_cost_daily', 'id::text'),
    ('job_postings', 'id::text'),
    ('market_instruments', 'id::text'),
    ('mbb_baseline', 'id::text'),
    ('peer_financials', 'id::text'),
    ('pipeline_logs', 'id::text'),
    ('raw_article_business_signals', 'id::text'),
    ('raw_article_financial_metrics', 'id::text'),
    ('recipients', 'id::text'),
    ('usage_logs', 'id::text'),
    ('user_events', 'id::text'),
    ('weak_signal_card_articles', 'id::text'),
    ('weak_signal_cards', 'id::text');

CREATE TEMP TABLE axis_v30_pre_drop_counts ON COMMIT DROP AS
SELECT
    t.source_table,
    (
        xpath(
            '/row/c/text()',
            query_to_xml(format('SELECT count(*) c FROM %I', t.source_table), false, true, '')
        )
    )[1]::text::bigint AS row_count
FROM axis_v30_tables_to_drop t
WHERE to_regclass('public.' || t.source_table) IS NOT NULL;

CREATE TEMP TABLE axis_v30_pre_core_counts ON COMMIT DROP AS
SELECT 'raw_articles'::text AS table_name, COUNT(*)::bigint AS row_count FROM raw_articles
UNION ALL
SELECT 'raw_article_source_metadata', COUNT(*)::bigint FROM raw_article_source_metadata
UNION ALL
SELECT 'raw_article_parse_results', COUNT(*)::bigint FROM raw_article_parse_results
UNION ALL
SELECT 'card_news', COUNT(*)::bigint FROM card_news
UNION ALL
SELECT 'peer_companies', COUNT(*)::bigint FROM peer_companies
UNION ALL
SELECT 'market_price_ohlcv', COUNT(*)::bigint FROM market_price_ohlcv
UNION ALL
SELECT 'crawl_cursors', COUNT(*)::bigint FROM crawl_cursors;

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

CREATE INDEX IF NOT EXISTS idx_legacy_records_source
    ON legacy_records (source_table, archived_at DESC);

CREATE INDEX IF NOT EXISTS idx_legacy_records_owner
    ON legacy_records (owner_table, owner_id)
    WHERE owner_table IS NOT NULL;

DO $$
DECLARE
    item RECORD;
BEGIN
    FOR item IN
        SELECT source_table, pk_expr
        FROM axis_v30_tables_to_drop
        WHERE to_regclass('public.' || source_table) IS NOT NULL
        ORDER BY source_table
    LOOP
        EXECUTE format(
            'INSERT INTO legacy_records (source_table, source_pk, payload)
             SELECT %L, (%s), to_jsonb(t)
             FROM %I t
             ON CONFLICT (source_table, source_pk) WHERE source_pk IS NOT NULL DO NOTHING',
            item.source_table,
            item.pk_expr,
            item.source_table
        );
    END LOOP;
END $$;

DO $$
DECLARE
    mismatch_count INT;
BEGIN
    WITH archived_counts AS (
        SELECT source_table, COUNT(*)::bigint AS row_count
        FROM legacy_records
        WHERE source_table IN (SELECT source_table FROM axis_v30_pre_drop_counts)
        GROUP BY source_table
    )
    SELECT COUNT(*) INTO mismatch_count
    FROM axis_v30_pre_drop_counts before_counts
    LEFT JOIN archived_counts after_counts
        ON after_counts.source_table = before_counts.source_table
    WHERE before_counts.row_count <> COALESCE(after_counts.row_count, 0);

    IF mismatch_count > 0 THEN
        RAISE EXCEPTION 'V30 aborted: legacy archive row counts do not match source tables';
    END IF;
END $$;

-- ============================================================
-- 1. Surviving table columns
-- ============================================================

ALTER TABLE raw_articles
    ADD COLUMN IF NOT EXISTS peer_company_ids TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN IF NOT EXISTS peer_company_links JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS financial_metrics JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS business_signals JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS crawl_events JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS legacy_payload JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE card_news
    ADD COLUMN IF NOT EXISTS source_raw_article_ids BIGINT[] NOT NULL DEFAULT '{}',
    ADD COLUMN IF NOT EXISTS source_articles JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS evidence_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS image_assets JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS legacy_payload JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE peer_companies
    ADD COLUMN IF NOT EXISTS financial_history JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS job_posting_history JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS legacy_payload JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE briefing_reports
    ADD COLUMN IF NOT EXISTS report_date DATE,
    ADD COLUMN IF NOT EXISTS period_label TEXT,
    ADD COLUMN IF NOT EXISTS key_summary TEXT,
    ADD COLUMN IF NOT EXISTS sk_implication TEXT,
    ADD COLUMN IF NOT EXISTS related_card_ids TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN IF NOT EXISTS related_raw_article_ids BIGINT[] NOT NULL DEFAULT '{}',
    ADD COLUMN IF NOT EXISTS recipients JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS delivery_history JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS legacy_payload JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE market_price_ohlcv
    ADD COLUMN IF NOT EXISTS instrument_payload JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE mixer_results
    ADD COLUMN IF NOT EXISTS source_analysis_id VARCHAR(100);

ALTER TABLE insight_reports
    ADD COLUMN IF NOT EXISTS source_analysis_id VARCHAR(100);

ALTER TABLE global_industry_trends
    ADD COLUMN IF NOT EXISTS source_analysis_id VARCHAR(100);

CREATE UNIQUE INDEX IF NOT EXISTS uq_mixer_results_source_analysis_id
    ON mixer_results (source_analysis_id)
    WHERE source_analysis_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_insight_reports_source_analysis_id
    ON insight_reports (source_analysis_id)
    WHERE source_analysis_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_global_industry_trends_source_analysis_id
    ON global_industry_trends (source_analysis_id)
    WHERE source_analysis_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_raw_articles_peer_company_ids
    ON raw_articles USING GIN(peer_company_ids);

CREATE INDEX IF NOT EXISTS idx_card_news_source_raw_article_ids
    ON card_news USING GIN(source_raw_article_ids);

-- ============================================================
-- 2. Fold legacy relations/facts into owner tables
-- ============================================================

WITH links AS (
    SELECT
        raw_article_id,
        array_agg(DISTINCT peer_company_id ORDER BY peer_company_id) AS peer_ids,
        jsonb_agg(to_jsonb(article_peer_companies) - 'raw_article_id' ORDER BY id) AS payload
    FROM article_peer_companies
    GROUP BY raw_article_id
)
UPDATE raw_articles ra
SET
    peer_company_ids = links.peer_ids,
    peer_company_links = links.payload
FROM links
WHERE ra.id = links.raw_article_id;

WITH metrics AS (
    SELECT
        raw_article_id,
        jsonb_agg(to_jsonb(raw_article_financial_metrics) - 'raw_article_id' ORDER BY id) AS payload
    FROM raw_article_financial_metrics
    GROUP BY raw_article_id
)
UPDATE raw_articles ra
SET financial_metrics = metrics.payload
FROM metrics
WHERE ra.id = metrics.raw_article_id;

WITH signals AS (
    SELECT
        raw_article_id,
        jsonb_agg(to_jsonb(raw_article_business_signals) - 'raw_article_id' ORDER BY id) AS payload
    FROM raw_article_business_signals
    GROUP BY raw_article_id
)
UPDATE raw_articles ra
SET business_signals = signals.payload
FROM signals
WHERE ra.id = signals.raw_article_id;

WITH crawl_items AS (
    SELECT
        cra.raw_article_id,
        jsonb_agg(
            (to_jsonb(cra) - 'raw_article_id') ||
            jsonb_build_object('crawl_run', to_jsonb(cr))
            ORDER BY cra.discovered_at, cra.id
        ) AS payload
    FROM crawl_run_articles cra
    LEFT JOIN crawl_runs cr
        ON cr.id = cra.crawl_run_id
    WHERE cra.raw_article_id IS NOT NULL
    GROUP BY cra.raw_article_id
)
UPDATE raw_articles ra
SET crawl_events = crawl_items.payload
FROM crawl_items
WHERE ra.id = crawl_items.raw_article_id;

UPDATE raw_articles ra
SET legacy_payload = jsonb_set(
        ra.legacy_payload,
        '{crawl_run}',
        to_jsonb(cr),
        true
    )
FROM crawl_runs cr
WHERE ra.crawl_run_id = cr.id;

WITH article_links AS (
    SELECT
        cna.card_news_id,
        array_agg(cna.raw_article_id ORDER BY cna.article_order NULLS LAST, cna.raw_article_id) AS article_ids,
        jsonb_agg(to_jsonb(cna) - 'card_news_id' ORDER BY cna.article_order NULLS LAST, cna.id) AS payload
    FROM card_news_articles cna
    GROUP BY cna.card_news_id
)
UPDATE card_news cn
SET
    source_raw_article_ids = article_links.article_ids,
    source_articles = article_links.payload
FROM article_links
WHERE cn.id = article_links.card_news_id;

UPDATE card_news cn
SET evidence_payload = jsonb_strip_nulls(jsonb_build_object(
        'source_links', ec.source_links,
        'provenance', ec.provenance,
        'financial_refs', ec.financial_refs,
        'mbb_refs', ec.mbb_refs,
        'financial_link', ec.financial_link,
        'evidence_version', ec.evidence_version,
        'pass', ec.pass,
        'missing', to_jsonb(ec.missing)
    ))
FROM evidence_chain ec
WHERE cn.id = COALESCE(ec.card_news_id, ec.issue_card_id);

WITH evidence_article_ids AS (
    SELECT
        COALESCE(ec.card_news_id, ec.issue_card_id) AS card_news_id,
        ARRAY(
            SELECT DISTINCT raw_id::bigint
            FROM jsonb_array_elements_text(ec.provenance -> 'raw_article_ids') AS raw_ids(raw_id)
            WHERE raw_id ~ '^[0-9]+$'
            ORDER BY raw_id::bigint
        ) AS raw_article_ids
    FROM evidence_chain ec
    WHERE jsonb_typeof(ec.provenance -> 'raw_article_ids') = 'array'
)
UPDATE card_news cn
SET source_raw_article_ids = CASE
        WHEN cardinality(cn.source_raw_article_ids) = 0 THEN evidence_article_ids.raw_article_ids
        ELSE cn.source_raw_article_ids
    END
FROM evidence_article_ids
WHERE cn.id = evidence_article_ids.card_news_id
  AND cardinality(evidence_article_ids.raw_article_ids) > 0;

WITH images AS (
    SELECT
        COALESCE(card_news_id, issue_card_id) AS card_news_id,
        jsonb_agg(to_jsonb(article_images) ORDER BY image_order NULLS LAST, id) AS payload
    FROM article_images
    WHERE COALESCE(card_news_id, issue_card_id) IS NOT NULL
    GROUP BY COALESCE(card_news_id, issue_card_id)
)
UPDATE card_news cn
SET image_assets = images.payload
FROM images
WHERE cn.id = images.card_news_id;

WITH weak_signals AS (
    SELECT
        card_news_id,
        jsonb_agg(to_jsonb(weak_signal_cards) ORDER BY detected_at DESC, id) AS payload
    FROM weak_signal_cards
    WHERE card_news_id IS NOT NULL
    GROUP BY card_news_id
)
UPDATE card_news cn
SET legacy_payload = jsonb_set(cn.legacy_payload, '{weak_signals}', weak_signals.payload, true)
FROM weak_signals
WHERE cn.id = weak_signals.card_news_id;

WITH financials AS (
    SELECT
        COALESCE(peer_company_id, peer_id) AS peer_company_id,
        jsonb_agg(to_jsonb(peer_financials) ORDER BY period, report_date NULLS LAST, id) AS payload
    FROM peer_financials
    GROUP BY COALESCE(peer_company_id, peer_id)
)
UPDATE peer_companies pc
SET financial_history = financials.payload
FROM financials
WHERE pc.id = financials.peer_company_id;

WITH postings AS (
    SELECT
        COALESCE(peer_company_id, peer_id) AS peer_company_id,
        jsonb_agg(to_jsonb(job_postings) ORDER BY snapshot_week DESC, id) AS payload
    FROM job_postings
    GROUP BY COALESCE(peer_company_id, peer_id)
)
UPDATE peer_companies pc
SET job_posting_history = postings.payload
FROM postings
WHERE pc.id = postings.peer_company_id;

WITH peer_analyses AS (
    SELECT
        peer_id,
        jsonb_agg(to_jsonb(al) ORDER BY al.created_at DESC, al.id DESC) AS payload
    FROM analysis_ledger al
    CROSS JOIN LATERAL jsonb_array_elements_text(al.peer_ids) AS peers(peer_id)
    WHERE lower(al.analysis_type) NOT IN ('mixer', 'mixeranalysis', 'insight', 'insightcascade', 'global', 'globaltrends', 'briefing')
    GROUP BY peer_id
)
UPDATE peer_companies pc
SET legacy_payload = jsonb_set(pc.legacy_payload, '{analysis_ledger}', peer_analyses.payload, true)
FROM peer_analyses
WHERE pc.id = peer_analyses.peer_id;

UPDATE market_price_ohlcv mp
SET instrument_payload = to_jsonb(mi)
FROM market_instruments mi
WHERE mp.instrument_id = mi.id;

-- ============================================================
-- 3. Move legacy analysis ledger rows to V29 read models
-- ============================================================

INSERT INTO mixer_results (
    source_analysis_id,
    title,
    input_peer_ids,
    input_card_ids,
    generated_implication,
    sk_ax_implication,
    final_one_liner,
    confidence,
    payload,
    created_at,
    updated_at
)
SELECT
    al.analysis_id,
    al.conclusion_one_liner,
    ARRAY(SELECT jsonb_array_elements_text(al.peer_ids)),
    ARRAY(SELECT jsonb_array_elements_text(al.source_card_ids)),
    jsonb_strip_nulls(jsonb_build_object(
        'strategy_label', al.strategy_label,
        'included_in_pack', al.included_in_pack,
        'superseded_by', al.superseded_by
    )),
    al.sk_ax_implication,
    al.conclusion_one_liner,
    al.confidence::numeric,
    to_jsonb(al),
    COALESCE(al.created_at, NOW()),
    COALESCE(al.created_at, NOW())
FROM analysis_ledger al
WHERE lower(al.analysis_type) IN ('mixer', 'mixeranalysis')
ON CONFLICT (source_analysis_id) WHERE source_analysis_id IS NOT NULL DO NOTHING;

INSERT INTO insight_reports (
    source_analysis_id,
    title,
    insight_type,
    status,
    focus_peer_ids,
    focus_card_ids,
    summary,
    final_one_liner,
    sk_ax_implication,
    source_card_ids,
    confidence,
    payload,
    created_at,
    updated_at
)
SELECT
    al.analysis_id,
    al.conclusion_one_liner,
    'legacy_ledger',
    'completed',
    ARRAY(SELECT jsonb_array_elements_text(al.peer_ids)),
    ARRAY(SELECT jsonb_array_elements_text(al.source_card_ids)),
    al.conclusion_one_liner,
    al.conclusion_one_liner,
    al.sk_ax_implication,
    ARRAY(SELECT jsonb_array_elements_text(al.source_card_ids)),
    al.confidence::numeric,
    to_jsonb(al),
    COALESCE(al.created_at, NOW()),
    COALESCE(al.created_at, NOW())
FROM analysis_ledger al
WHERE lower(al.analysis_type) IN ('insight', 'insightcascade')
ON CONFLICT (source_analysis_id) WHERE source_analysis_id IS NOT NULL DO NOTHING;

INSERT INTO global_industry_trends (
    source_analysis_id,
    trend_date,
    industry,
    region,
    keyword,
    keyword_category,
    title,
    summary,
    mention_count,
    confidence,
    related_peer_ids,
    related_card_ids,
    sk_ax_implication,
    payload,
    created_at,
    updated_at
)
SELECT
    al.analysis_id,
    COALESCE(al.created_at::date, CURRENT_DATE),
    'legacy',
    'global',
    LEFT(COALESCE(NULLIF(al.strategy_label, ''), al.analysis_id, 'global'), 120),
    'legacy_ledger',
    al.conclusion_one_liner,
    al.conclusion_one_liner,
    0,
    al.confidence::numeric,
    ARRAY(SELECT jsonb_array_elements_text(al.peer_ids)),
    ARRAY(SELECT jsonb_array_elements_text(al.source_card_ids)),
    al.sk_ax_implication,
    to_jsonb(al),
    COALESCE(al.created_at, NOW()),
    COALESCE(al.created_at, NOW())
FROM analysis_ledger al
WHERE lower(al.analysis_type) IN ('global', 'globaltrends')
ON CONFLICT (source_analysis_id) WHERE source_analysis_id IS NOT NULL DO NOTHING;

INSERT INTO briefing_reports (
    id,
    title,
    briefing_type,
    date_from,
    date_to,
    status,
    progress,
    payload,
    confidence,
    provenance,
    created_at,
    completed_at,
    key_summary,
    sk_implication,
    related_card_ids,
    legacy_payload
)
SELECT
    LEFT('BR-LEGACY-' || al.id::text, 40),
    al.conclusion_one_liner,
    'daily',
    COALESCE(al.created_at::date, CURRENT_DATE),
    COALESCE(al.created_at::date, CURRENT_DATE),
    'completed',
    1.0,
    to_jsonb(al),
    al.confidence::numeric,
    jsonb_strip_nulls(jsonb_build_object(
        'source', 'analysis_ledger',
        'analysis_id', al.analysis_id,
        'langfuse_trace_id', al.langfuse_trace_id
    )),
    COALESCE(al.created_at, NOW()),
    COALESCE(al.created_at, NOW()),
    al.conclusion_one_liner,
    al.sk_ax_implication,
    ARRAY(SELECT jsonb_array_elements_text(al.source_card_ids)),
    jsonb_build_object('analysis_ledger', to_jsonb(al))
FROM analysis_ledger al
WHERE lower(al.analysis_type) = 'briefing'
ON CONFLICT (id) DO NOTHING;

-- ============================================================
-- 4. Collapse briefing relation/history tables into briefing_reports
-- ============================================================

WITH report_cards AS (
    SELECT
        briefing_report_id,
        array_agg(card_news_id ORDER BY card_order NULLS LAST, card_news_id) AS card_ids
    FROM briefing_report_cards
    GROUP BY briefing_report_id
)
UPDATE briefing_reports br
SET related_card_ids = report_cards.card_ids
FROM report_cards
WHERE br.id = report_cards.briefing_report_id;

WITH report_articles AS (
    SELECT
        briefing_report_id,
        array_agg(raw_article_id ORDER BY article_order NULLS LAST, raw_article_id) AS article_ids
    FROM briefing_report_articles
    GROUP BY briefing_report_id
)
UPDATE briefing_reports br
SET related_raw_article_ids = report_articles.article_ids
FROM report_articles
WHERE br.id = report_articles.briefing_report_id;

WITH report_recipients AS (
    SELECT
        brc.briefing_report_id,
        jsonb_agg(
            jsonb_build_object(
                'recipient', to_jsonb(r),
                'delivery', to_jsonb(brc) - 'recipient_id' - 'briefing_report_id'
            )
            ORDER BY brc.created_at, brc.id
        ) AS payload
    FROM briefing_recipients brc
    JOIN recipients r
        ON r.id = brc.recipient_id
    GROUP BY brc.briefing_report_id
)
UPDATE briefing_reports br
SET recipients = report_recipients.payload
FROM report_recipients
WHERE br.id = report_recipients.briefing_report_id;

WITH report_history AS (
    SELECT
        briefing_report_id,
        jsonb_agg(to_jsonb(briefing_history) ORDER BY sent_at DESC, id DESC) AS payload
    FROM briefing_history
    WHERE briefing_report_id IS NOT NULL
    GROUP BY briefing_report_id
)
UPDATE briefing_reports br
SET delivery_history = report_history.payload
FROM report_history
WHERE br.id = report_history.briefing_report_id;

INSERT INTO briefing_reports (
    id,
    title,
    briefing_type,
    date_from,
    date_to,
    status,
    progress,
    payload,
    created_at,
    completed_at,
    report_date,
    key_summary,
    related_card_ids,
    recipients,
    delivery_history,
    legacy_payload
)
SELECT
    LEFT('BH-' || bh.id::text, 40),
    COALESCE(NULLIF(bh.subject, ''), 'Daily briefing ' || bh.briefing_date::text),
    'daily',
    bh.briefing_date,
    bh.briefing_date,
    CASE
        WHEN bh.status = 'failed' THEN 'failed'
        WHEN bh.status = 'sent' THEN 'completed'
        ELSE 'completed_partial'
    END,
    1.0,
    jsonb_build_object('briefing_history', to_jsonb(bh)),
    COALESCE(bh.created_at, bh.sent_at, NOW()),
    bh.sent_at,
    bh.briefing_date,
    bh.body_preview,
    COALESCE(bh.card_ids, '{}'),
    jsonb_build_array(to_jsonb(r)),
    jsonb_build_array(to_jsonb(bh)),
    jsonb_build_object('briefing_history', to_jsonb(bh))
FROM briefing_history bh
JOIN recipients r
    ON r.id = bh.recipient_id
ON CONFLICT (id) DO NOTHING;

-- ============================================================
-- 5. Drop legacy tables after archive/copy
-- ============================================================

ALTER TABLE raw_articles
    DROP CONSTRAINT IF EXISTS fk_raw_articles_crawl_run;

ALTER TABLE market_price_ohlcv
    DROP CONSTRAINT IF EXISTS fk_market_price_ohlcv_instrument;

DROP TABLE IF EXISTS analysis_ledger_card_news;
DROP TABLE IF EXISTS analysis_ledger_peer_companies;
DROP TABLE IF EXISTS weak_signal_card_articles;
DROP TABLE IF EXISTS briefing_history_cards;
DROP TABLE IF EXISTS briefing_recipients;
DROP TABLE IF EXISTS briefing_report_articles;
DROP TABLE IF EXISTS briefing_report_cards;
DROP TABLE IF EXISTS card_news_articles;
DROP TABLE IF EXISTS article_peer_companies;
DROP TABLE IF EXISTS feedback;
DROP TABLE IF EXISTS chat_turns;
DROP TABLE IF EXISTS chat_sessions;
DROP TABLE IF EXISTS usage_logs;
DROP TABLE IF EXISTS user_events;
DROP TABLE IF EXISTS crawl_logs;
DROP TABLE IF EXISTS crawl_run_articles;
DROP TABLE IF EXISTS pipeline_logs;
DROP TABLE IF EXISTS audit_logs;
DROP TABLE IF EXISTS cost_daily_billed;
DROP TABLE IF EXISTS infra_cost_daily;
DROP TABLE IF EXISTS article_images;
DROP TABLE IF EXISTS evidence_chain;
DROP TABLE IF EXISTS weak_signal_cards;
DROP TABLE IF EXISTS analysis_ledger;
DROP TABLE IF EXISTS job_postings;
DROP TABLE IF EXISTS peer_financials;
DROP TABLE IF EXISTS market_instruments;
DROP TABLE IF EXISTS mbb_baseline;
DROP TABLE IF EXISTS briefing_history;
DROP TABLE IF EXISTS recipients;
DROP TABLE IF EXISTS golden_set;
DROP TABLE IF EXISTS raw_article_financial_metrics;
DROP TABLE IF EXISTS raw_article_business_signals;
DROP TABLE IF EXISTS crawl_runs;

-- ============================================================
-- 6. Post checks and comments
-- ============================================================

DO $$
DECLARE
    changed_count INT;
    remaining_count INT;
BEGIN
    WITH current_counts AS (
        SELECT 'raw_articles'::text AS table_name, COUNT(*)::bigint AS row_count FROM raw_articles
        UNION ALL
        SELECT 'raw_article_source_metadata', COUNT(*)::bigint FROM raw_article_source_metadata
        UNION ALL
        SELECT 'raw_article_parse_results', COUNT(*)::bigint FROM raw_article_parse_results
        UNION ALL
        SELECT 'card_news', COUNT(*)::bigint FROM card_news
        UNION ALL
        SELECT 'peer_companies', COUNT(*)::bigint FROM peer_companies
        UNION ALL
        SELECT 'market_price_ohlcv', COUNT(*)::bigint FROM market_price_ohlcv
        UNION ALL
        SELECT 'crawl_cursors', COUNT(*)::bigint FROM crawl_cursors
    )
    SELECT COUNT(*) INTO changed_count
    FROM axis_v30_pre_core_counts before_counts
    JOIN current_counts after_counts
        ON after_counts.table_name = before_counts.table_name
    WHERE after_counts.row_count <> before_counts.row_count;

    IF changed_count > 0 THEN
        RAISE EXCEPTION 'V30 aborted: protected core table row count changed';
    END IF;

    SELECT COUNT(*) INTO remaining_count
    FROM axis_v30_tables_to_drop
    WHERE to_regclass('public.' || source_table) IS NOT NULL;

    IF remaining_count > 0 THEN
        RAISE EXCEPTION 'V30 aborted: at least one legacy table still exists';
    END IF;
END $$;

COMMENT ON TABLE legacy_records IS
    'Row-level archive for tables collapsed by V30. This is data-retention storage, not part of the product ERD.';
COMMENT ON COLUMN raw_articles.peer_company_ids IS
    'Collapsed from article_peer_companies.';
COMMENT ON COLUMN raw_articles.financial_metrics IS
    'Collapsed from raw_article_financial_metrics.';
COMMENT ON COLUMN raw_articles.business_signals IS
    'Collapsed from raw_article_business_signals.';
COMMENT ON COLUMN raw_articles.crawl_events IS
    'Collapsed from crawl_run_articles/crawl_runs.';
COMMENT ON COLUMN card_news.source_raw_article_ids IS
    'Collapsed from card_news_articles and evidence provenance.';
COMMENT ON COLUMN card_news.evidence_payload IS
    'Collapsed from evidence_chain.';
COMMENT ON COLUMN card_news.image_assets IS
    'Collapsed from article_images.';
COMMENT ON COLUMN peer_companies.financial_history IS
    'Collapsed from peer_financials.';
COMMENT ON COLUMN briefing_reports.delivery_history IS
    'Collapsed from briefing_history and delivery relation tables.';
