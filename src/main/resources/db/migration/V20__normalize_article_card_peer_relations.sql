-- V20: article/card/peer relation normalization.
--
-- Safety policy:
--   * additive only: no DELETE/TRUNCATE/DROP/RENAME
--   * preserve raw_articles, article_images, card_news data
--   * backfill first, verify orphan rows, then add indexes/FKs
--   * legacy columns remain for rollout compatibility

-- ------------------------------------------------------------------
-- crawl state tables are currently created by axis-ai helper code in
-- some environments. Bring them under Flyway ownership without changing
-- existing rows.
-- ------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS crawl_cursors (
    source_name VARCHAR(100) PRIMARY KEY,
    cursor_date DATE NOT NULL,
    until_date DATE NOT NULL,
    window_days INT NOT NULL,
    max_windows_per_run INT NOT NULL DEFAULT 1,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

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

-- ------------------------------------------------------------------
-- raw_articles -> crawl_runs
-- ------------------------------------------------------------------
ALTER TABLE raw_articles
    ADD COLUMN IF NOT EXISTS crawl_run_id UUID NULL;

WITH valid_metadata_run_ids AS (
    SELECT
        id AS raw_article_id,
        (metadata ->> 'crawl_run_id')::uuid AS crawl_run_uuid
    FROM raw_articles
    WHERE metadata ? 'crawl_run_id'
      AND (metadata ->> 'crawl_run_id') ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
)
UPDATE raw_articles ra
SET crawl_run_id = v.crawl_run_uuid
FROM valid_metadata_run_ids v
JOIN crawl_runs cr ON cr.id = v.crawl_run_uuid
WHERE ra.id = v.raw_article_id
  AND ra.crawl_run_id IS NULL;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM raw_articles ra
        LEFT JOIN crawl_runs cr ON cr.id = ra.crawl_run_id
        WHERE ra.crawl_run_id IS NOT NULL
          AND cr.id IS NULL
    ) THEN
        RAISE EXCEPTION 'V20 aborted: raw_articles.crawl_run_id orphan rows exist';
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_raw_articles_crawl_run_id
    ON raw_articles (crawl_run_id);

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
            REFERENCES crawl_runs(id);
    END IF;
END $$;

-- ------------------------------------------------------------------
-- article_images legacy aliases:
--   article_id     -> raw_article_id
--   issue_card_id  -> card_news_id
-- Existing legacy columns are intentionally retained.
-- ------------------------------------------------------------------
ALTER TABLE article_images
    ADD COLUMN IF NOT EXISTS raw_article_id BIGINT NULL,
    ADD COLUMN IF NOT EXISTS card_news_id VARCHAR(50) NULL,
    ADD COLUMN IF NOT EXISTS image_order INT NULL,
    ADD COLUMN IF NOT EXISTS caption TEXT NULL,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NULL;

UPDATE article_images
SET raw_article_id = article_id
WHERE raw_article_id IS NULL
  AND article_id IS NOT NULL;

UPDATE article_images
SET card_news_id = issue_card_id
WHERE card_news_id IS NULL
  AND issue_card_id IS NOT NULL;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM article_images ai
        LEFT JOIN raw_articles ra ON ra.id = ai.raw_article_id
        WHERE ai.raw_article_id IS NOT NULL
          AND ra.id IS NULL
    ) THEN
        RAISE EXCEPTION 'V20 aborted: article_images.raw_article_id orphan rows exist';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM article_images ai
        LEFT JOIN card_news cn ON cn.id = ai.card_news_id
        WHERE ai.card_news_id IS NOT NULL
          AND cn.id IS NULL
    ) THEN
        RAISE EXCEPTION 'V20 aborted: article_images.card_news_id orphan rows exist';
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_article_images_raw_article_id
    ON article_images (raw_article_id);
CREATE INDEX IF NOT EXISTS idx_article_images_card_news_id
    ON article_images (card_news_id);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_article_images_raw_article'
          AND conrelid = 'article_images'::regclass
    ) THEN
        ALTER TABLE article_images
            ADD CONSTRAINT fk_article_images_raw_article
            FOREIGN KEY (raw_article_id)
            REFERENCES raw_articles(id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_article_images_card_news'
          AND conrelid = 'article_images'::regclass
    ) THEN
        ALTER TABLE article_images
            ADD CONSTRAINT fk_article_images_card_news
            FOREIGN KEY (card_news_id)
            REFERENCES card_news(id)
            ON DELETE SET NULL;
    END IF;
END $$;

-- ------------------------------------------------------------------
-- card_news -> peer_companies alias.
-- card_news.company remains the current writer-facing column.
-- ------------------------------------------------------------------
ALTER TABLE card_news
    ADD COLUMN IF NOT EXISTS peer_company_id VARCHAR(50) NULL;

UPDATE card_news cn
SET peer_company_id = cn.company
FROM peer_companies pc
WHERE cn.peer_company_id IS NULL
  AND cn.company = pc.id;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM card_news cn
        LEFT JOIN peer_companies pc ON pc.id = cn.peer_company_id
        WHERE cn.peer_company_id IS NOT NULL
          AND pc.id IS NULL
    ) THEN
        RAISE EXCEPTION 'V20 aborted: card_news.peer_company_id orphan rows exist';
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_card_news_peer_company_id
    ON card_news (peer_company_id);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_card_news_peer_company'
          AND conrelid = 'card_news'::regclass
    ) THEN
        ALTER TABLE card_news
            ADD CONSTRAINT fk_card_news_peer_company
            FOREIGN KEY (peer_company_id)
            REFERENCES peer_companies(id);
    END IF;
END $$;

-- ------------------------------------------------------------------
-- evidence_chain legacy alias:
--   issue_card_id -> card_news_id
-- Existing PK and FK on issue_card_id remain.
-- ------------------------------------------------------------------
ALTER TABLE evidence_chain
    ADD COLUMN IF NOT EXISTS card_news_id VARCHAR(50) NULL;

UPDATE evidence_chain
SET card_news_id = issue_card_id
WHERE card_news_id IS NULL
  AND issue_card_id IS NOT NULL;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM evidence_chain ec
        LEFT JOIN card_news cn ON cn.id = ec.card_news_id
        WHERE ec.card_news_id IS NOT NULL
          AND cn.id IS NULL
    ) THEN
        RAISE EXCEPTION 'V20 aborted: evidence_chain.card_news_id orphan rows exist';
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_evidence_chain_card_news_id
    ON evidence_chain (card_news_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_evidence_chain_card_news_id
    ON evidence_chain (card_news_id)
    WHERE card_news_id IS NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_evidence_chain_card_news'
          AND conrelid = 'evidence_chain'::regclass
    ) THEN
        ALTER TABLE evidence_chain
            ADD CONSTRAINT fk_evidence_chain_card_news
            FOREIGN KEY (card_news_id)
            REFERENCES card_news(id)
            ON DELETE CASCADE;
    END IF;
END $$;

-- ------------------------------------------------------------------
-- card_news_articles: card_news can be based on multiple raw articles.
-- Backfill from evidence_chain.provenance.raw_article_ids.
-- ------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS card_news_articles (
    id BIGSERIAL PRIMARY KEY,
    card_news_id VARCHAR(50) NOT NULL,
    raw_article_id BIGINT NOT NULL,
    article_order INT,
    relation_source VARCHAR(40) NOT NULL DEFAULT 'evidence_provenance',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_card_news_articles_card_news
        FOREIGN KEY (card_news_id)
        REFERENCES card_news(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_card_news_articles_raw_article
        FOREIGN KEY (raw_article_id)
        REFERENCES raw_articles(id)
        ON DELETE RESTRICT,

    CONSTRAINT uq_card_news_article
        UNIQUE (card_news_id, raw_article_id)
);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM evidence_chain ec
        CROSS JOIN LATERAL jsonb_array_elements_text(ec.provenance -> 'raw_article_ids') AS rid(raw_article_id)
        WHERE jsonb_typeof(ec.provenance -> 'raw_article_ids') = 'array'
          AND rid.raw_article_id !~ '^[0-9]+$'
    ) THEN
        RAISE EXCEPTION 'V20 aborted: evidence_chain.provenance.raw_article_ids contains non-numeric values';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM evidence_chain ec
        CROSS JOIN LATERAL jsonb_array_elements_text(ec.provenance -> 'raw_article_ids') AS rid(raw_article_id)
        LEFT JOIN raw_articles ra ON ra.id = rid.raw_article_id::bigint
        WHERE jsonb_typeof(ec.provenance -> 'raw_article_ids') = 'array'
          AND ra.id IS NULL
    ) THEN
        RAISE EXCEPTION 'V20 aborted: evidence_chain raw_article_ids orphan rows exist';
    END IF;
END $$;

INSERT INTO card_news_articles (
    card_news_id,
    raw_article_id,
    article_order,
    relation_source
)
SELECT
    ec.issue_card_id,
    rid.raw_article_id::bigint,
    rid.ordinality::int,
    'evidence_provenance'
FROM evidence_chain ec
CROSS JOIN LATERAL jsonb_array_elements_text(ec.provenance -> 'raw_article_ids')
    WITH ORDINALITY AS rid(raw_article_id, ordinality)
WHERE jsonb_typeof(ec.provenance -> 'raw_article_ids') = 'array'
ON CONFLICT (card_news_id, raw_article_id) DO NOTHING;

CREATE INDEX IF NOT EXISTS idx_card_news_articles_raw_article_id
    ON card_news_articles (raw_article_id);
CREATE INDEX IF NOT EXISTS idx_card_news_articles_card_news_order
    ON card_news_articles (card_news_id, article_order);

-- ------------------------------------------------------------------
-- article_peer_companies: raw article <-> peer company N:M.
-- Backfill by matching raw_articles.company/matched_companies tokens to
-- peer_companies.id/name/keywords. Unknown display tokens are preserved
-- in raw_articles and intentionally not deleted.
-- ------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS article_peer_companies (
    id BIGSERIAL PRIMARY KEY,
    raw_article_id BIGINT NOT NULL,
    peer_company_id VARCHAR(50) NOT NULL,
    relevance_score DECIMAL(5,4),
    matched_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_article_peer_companies_article
        FOREIGN KEY (raw_article_id)
        REFERENCES raw_articles(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_article_peer_companies_peer_company
        FOREIGN KEY (peer_company_id)
        REFERENCES peer_companies(id)
        ON DELETE RESTRICT,

    CONSTRAINT uq_article_peer_company
        UNIQUE (raw_article_id, peer_company_id)
);

WITH company_tokens AS (
    SELECT
        ra.id AS raw_article_id,
        ra.relevance_score,
        'company' AS source_field,
        token.company_token
    FROM raw_articles ra
    CROSS JOIN LATERAL jsonb_array_elements_text(ra.company) AS token(company_token)
    WHERE jsonb_typeof(ra.company) = 'array'

    UNION ALL

    SELECT
        ra.id AS raw_article_id,
        ra.relevance_score,
        'matched_companies' AS source_field,
        token.company_token
    FROM raw_articles ra
    CROSS JOIN LATERAL jsonb_array_elements_text(ra.matched_companies) AS token(company_token)
    WHERE jsonb_typeof(ra.matched_companies) = 'array'
),
matched AS (
    SELECT
        t.raw_article_id,
        pc.id AS peer_company_id,
        t.relevance_score,
        t.source_field,
        t.company_token
    FROM company_tokens t
    JOIN peer_companies pc
      ON lower(t.company_token) = lower(pc.id)
      OR lower(t.company_token) = lower(pc.name)
      OR EXISTS (
          SELECT 1
          FROM unnest(pc.keywords) AS kw(keyword)
          WHERE lower(t.company_token) = lower(kw.keyword)
      )
)
INSERT INTO article_peer_companies (
    raw_article_id,
    peer_company_id,
    relevance_score,
    matched_reason
)
SELECT
    raw_article_id,
    peer_company_id,
    MAX(
        CASE
            WHEN relevance_score BETWEEN 0.0 AND 1.0
                THEN relevance_score::numeric(5,4)
            ELSE NULL
        END
    ) AS relevance_score,
    'backfill:' || string_agg(DISTINCT source_field || '=' || company_token, ', ') AS matched_reason
FROM matched
GROUP BY raw_article_id, peer_company_id
ON CONFLICT (raw_article_id, peer_company_id) DO NOTHING;

CREATE INDEX IF NOT EXISTS idx_article_peer_companies_peer_company_id
    ON article_peer_companies (peer_company_id);
CREATE INDEX IF NOT EXISTS idx_article_peer_companies_raw_article_id
    ON article_peer_companies (raw_article_id);

-- ------------------------------------------------------------------
-- Peer table aliases for future naming consistency. Existing peer_id
-- columns remain the writer-facing columns until application code moves.
-- ------------------------------------------------------------------
ALTER TABLE peer_financials
    ADD COLUMN IF NOT EXISTS peer_company_id VARCHAR(50) NULL;

UPDATE peer_financials
SET peer_company_id = peer_id
WHERE peer_company_id IS NULL
  AND peer_id IS NOT NULL;

ALTER TABLE job_postings
    ADD COLUMN IF NOT EXISTS peer_company_id VARCHAR(50) NULL;

UPDATE job_postings
SET peer_company_id = peer_id
WHERE peer_company_id IS NULL
  AND peer_id IS NOT NULL;

ALTER TABLE weak_signal_cards
    ADD COLUMN IF NOT EXISTS peer_company_id VARCHAR(50) NULL;

UPDATE weak_signal_cards
SET peer_company_id = peer_id
WHERE peer_company_id IS NULL
  AND peer_id IS NOT NULL;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM peer_financials pf
        LEFT JOIN peer_companies pc ON pc.id = pf.peer_company_id
        WHERE pf.peer_company_id IS NOT NULL
          AND pc.id IS NULL
    ) THEN
        RAISE EXCEPTION 'V20 aborted: peer_financials.peer_company_id orphan rows exist';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM job_postings jp
        LEFT JOIN peer_companies pc ON pc.id = jp.peer_company_id
        WHERE jp.peer_company_id IS NOT NULL
          AND pc.id IS NULL
    ) THEN
        RAISE EXCEPTION 'V20 aborted: job_postings.peer_company_id orphan rows exist';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM weak_signal_cards ws
        LEFT JOIN peer_companies pc ON pc.id = ws.peer_company_id
        WHERE ws.peer_company_id IS NOT NULL
          AND pc.id IS NULL
    ) THEN
        RAISE EXCEPTION 'V20 aborted: weak_signal_cards.peer_company_id orphan rows exist';
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_peer_financials_peer_company_id
    ON peer_financials (peer_company_id);
CREATE INDEX IF NOT EXISTS idx_job_postings_peer_company_id
    ON job_postings (peer_company_id);
CREATE INDEX IF NOT EXISTS idx_weak_signal_cards_peer_company_id
    ON weak_signal_cards (peer_company_id);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_peer_financials_peer_company'
          AND conrelid = 'peer_financials'::regclass
    ) THEN
        ALTER TABLE peer_financials
            ADD CONSTRAINT fk_peer_financials_peer_company
            FOREIGN KEY (peer_company_id)
            REFERENCES peer_companies(id);
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_job_postings_peer_company'
          AND conrelid = 'job_postings'::regclass
    ) THEN
        ALTER TABLE job_postings
            ADD CONSTRAINT fk_job_postings_peer_company
            FOREIGN KEY (peer_company_id)
            REFERENCES peer_companies(id);
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_weak_signal_cards_peer_company'
          AND conrelid = 'weak_signal_cards'::regclass
    ) THEN
        ALTER TABLE weak_signal_cards
            ADD CONSTRAINT fk_weak_signal_cards_peer_company
            FOREIGN KEY (peer_company_id)
            REFERENCES peer_companies(id);
    END IF;
END $$;
