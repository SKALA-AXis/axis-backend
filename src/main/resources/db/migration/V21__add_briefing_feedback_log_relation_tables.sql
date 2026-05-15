-- V21: briefing, feedback, weak-signal, analysis-ledger, and log relations.
--
-- Safety policy:
--   * additive only: no DELETE/TRUNCATE/DROP/RENAME
--   * legacy polymorphic/id-array columns remain for compatibility
--   * backfill first, verify orphan rows, then add indexes/FKs

-- ------------------------------------------------------------------
-- briefing_history -> briefing_reports alias.
-- Daily email history can remain independent; this nullable link is used
-- when a sent email corresponds to a generated briefing report.
-- ------------------------------------------------------------------
ALTER TABLE briefing_history
    ADD COLUMN IF NOT EXISTS briefing_report_id VARCHAR(40) NULL;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM briefing_history bh
        LEFT JOIN briefing_reports br ON br.id = bh.briefing_report_id
        WHERE bh.briefing_report_id IS NOT NULL
          AND br.id IS NULL
    ) THEN
        RAISE EXCEPTION 'V21 aborted: briefing_history.briefing_report_id orphan rows exist';
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_briefing_history_briefing_report_id
    ON briefing_history (briefing_report_id);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_briefing_history_briefing_report'
          AND conrelid = 'briefing_history'::regclass
    ) THEN
        ALTER TABLE briefing_history
            ADD CONSTRAINT fk_briefing_history_briefing_report
            FOREIGN KEY (briefing_report_id)
            REFERENCES briefing_reports(id)
            ON DELETE SET NULL;
    END IF;
END $$;

-- ------------------------------------------------------------------
-- briefing report/card/article/recipient mapping tables.
-- ------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS briefing_report_cards (
    id BIGSERIAL PRIMARY KEY,
    briefing_report_id VARCHAR(40) NOT NULL,
    card_news_id VARCHAR(50) NOT NULL,
    card_order INT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_briefing_report_cards_report
        FOREIGN KEY (briefing_report_id)
        REFERENCES briefing_reports(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_briefing_report_cards_card_news
        FOREIGN KEY (card_news_id)
        REFERENCES card_news(id)
        ON DELETE RESTRICT,

    CONSTRAINT uq_briefing_report_card
        UNIQUE (briefing_report_id, card_news_id)
);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM briefing_reports br
        CROSS JOIN LATERAL jsonb_array_elements_text(br.provenance -> 'source_card_ids') AS cid(card_news_id)
        WHERE jsonb_typeof(br.provenance -> 'source_card_ids') = 'array'
          AND NOT EXISTS (
              SELECT 1 FROM card_news cn WHERE cn.id = cid.card_news_id
          )
    ) THEN
        RAISE EXCEPTION 'V21 aborted: briefing_reports.provenance.source_card_ids orphan rows exist';
    END IF;
END $$;

INSERT INTO briefing_report_cards (
    briefing_report_id,
    card_news_id,
    card_order
)
SELECT
    br.id,
    cid.card_news_id,
    cid.ordinality::int
FROM briefing_reports br
CROSS JOIN LATERAL jsonb_array_elements_text(br.provenance -> 'source_card_ids')
    WITH ORDINALITY AS cid(card_news_id, ordinality)
WHERE jsonb_typeof(br.provenance -> 'source_card_ids') = 'array'
ON CONFLICT (briefing_report_id, card_news_id) DO NOTHING;

CREATE INDEX IF NOT EXISTS idx_briefing_report_cards_card_news_id
    ON briefing_report_cards (card_news_id);
CREATE INDEX IF NOT EXISTS idx_briefing_report_cards_report_order
    ON briefing_report_cards (briefing_report_id, card_order);

CREATE TABLE IF NOT EXISTS briefing_report_articles (
    id BIGSERIAL PRIMARY KEY,
    briefing_report_id VARCHAR(40) NOT NULL,
    raw_article_id BIGINT NOT NULL,
    article_order INT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_briefing_report_articles_report
        FOREIGN KEY (briefing_report_id)
        REFERENCES briefing_reports(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_briefing_report_articles_article
        FOREIGN KEY (raw_article_id)
        REFERENCES raw_articles(id)
        ON DELETE RESTRICT,

    CONSTRAINT uq_briefing_report_article
        UNIQUE (briefing_report_id, raw_article_id)
);

INSERT INTO briefing_report_articles (
    briefing_report_id,
    raw_article_id,
    article_order
)
SELECT
    brc.briefing_report_id,
    cna.raw_article_id,
    MIN(cna.article_order) AS article_order
FROM briefing_report_cards brc
JOIN card_news_articles cna ON cna.card_news_id = brc.card_news_id
GROUP BY brc.briefing_report_id, cna.raw_article_id
ON CONFLICT (briefing_report_id, raw_article_id) DO NOTHING;

CREATE INDEX IF NOT EXISTS idx_briefing_report_articles_raw_article_id
    ON briefing_report_articles (raw_article_id);
CREATE INDEX IF NOT EXISTS idx_briefing_report_articles_report_order
    ON briefing_report_articles (briefing_report_id, article_order);

CREATE TABLE IF NOT EXISTS briefing_recipients (
    id BIGSERIAL PRIMARY KEY,
    briefing_report_id VARCHAR(40) NOT NULL,
    recipient_id BIGINT NOT NULL,
    delivery_status VARCHAR(50),
    sent_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_briefing_recipients_report
        FOREIGN KEY (briefing_report_id)
        REFERENCES briefing_reports(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_briefing_recipients_recipient
        FOREIGN KEY (recipient_id)
        REFERENCES recipients(id)
        ON DELETE RESTRICT,

    CONSTRAINT uq_briefing_recipient
        UNIQUE (briefing_report_id, recipient_id)
);

CREATE INDEX IF NOT EXISTS idx_briefing_recipients_recipient_id
    ON briefing_recipients (recipient_id);
CREATE INDEX IF NOT EXISTS idx_briefing_recipients_status
    ON briefing_recipients (delivery_status);

-- Daily email history still stores card_ids as an array. Add a normalized
-- mapping table without removing the legacy array.
CREATE TABLE IF NOT EXISTS briefing_history_cards (
    id BIGSERIAL PRIMARY KEY,
    briefing_history_id BIGINT NOT NULL,
    card_news_id VARCHAR(50) NOT NULL,
    card_order INT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_briefing_history_cards_history
        FOREIGN KEY (briefing_history_id)
        REFERENCES briefing_history(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_briefing_history_cards_card_news
        FOREIGN KEY (card_news_id)
        REFERENCES card_news(id)
        ON DELETE RESTRICT,

    CONSTRAINT uq_briefing_history_card
        UNIQUE (briefing_history_id, card_news_id)
);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM briefing_history bh
        CROSS JOIN LATERAL unnest(bh.card_ids) WITH ORDINALITY AS cid(card_news_id, ordinality)
        WHERE bh.card_ids IS NOT NULL
          AND NOT EXISTS (
              SELECT 1 FROM card_news cn WHERE cn.id = cid.card_news_id
          )
    ) THEN
        RAISE EXCEPTION 'V21 aborted: briefing_history.card_ids orphan rows exist';
    END IF;
END $$;

INSERT INTO briefing_history_cards (
    briefing_history_id,
    card_news_id,
    card_order
)
SELECT
    bh.id,
    cid.card_news_id,
    cid.ordinality::int
FROM briefing_history bh
CROSS JOIN LATERAL unnest(bh.card_ids) WITH ORDINALITY AS cid(card_news_id, ordinality)
WHERE bh.card_ids IS NOT NULL
ON CONFLICT (briefing_history_id, card_news_id) DO NOTHING;

CREATE INDEX IF NOT EXISTS idx_briefing_history_cards_card_news_id
    ON briefing_history_cards (card_news_id);
CREATE INDEX IF NOT EXISTS idx_briefing_history_cards_history_order
    ON briefing_history_cards (briefing_history_id, card_order);

-- ------------------------------------------------------------------
-- feedback typed nullable FKs. artifact_type/artifact_id remain the
-- writer-facing polymorphic columns until code is moved.
-- ------------------------------------------------------------------
ALTER TABLE feedback
    ADD COLUMN IF NOT EXISTS card_news_id VARCHAR(50) NULL,
    ADD COLUMN IF NOT EXISTS briefing_report_id VARCHAR(40) NULL,
    ADD COLUMN IF NOT EXISTS chat_turn_id BIGINT NULL;

UPDATE feedback f
SET card_news_id = f.artifact_id
FROM card_news cn
WHERE f.card_news_id IS NULL
  AND f.artifact_type = 'card'
  AND cn.id = f.artifact_id;

UPDATE feedback f
SET briefing_report_id = f.artifact_id
FROM briefing_reports br
WHERE f.briefing_report_id IS NULL
  AND f.artifact_type = 'briefing'
  AND br.id = f.artifact_id;

WITH valid_chat_turn_feedback AS (
    SELECT
        id AS feedback_id,
        artifact_id::bigint AS parsed_chat_turn_id
    FROM feedback
    WHERE artifact_type = 'chat_turn'
      AND artifact_id ~ '^[0-9]+$'
)
UPDATE feedback f
SET chat_turn_id = v.parsed_chat_turn_id
FROM valid_chat_turn_feedback v
JOIN chat_turns ct ON ct.id = v.parsed_chat_turn_id
WHERE f.id = v.feedback_id
  AND f.chat_turn_id IS NULL;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM feedback f
        LEFT JOIN card_news cn ON cn.id = f.card_news_id
        WHERE f.card_news_id IS NOT NULL
          AND cn.id IS NULL
    ) THEN
        RAISE EXCEPTION 'V21 aborted: feedback.card_news_id orphan rows exist';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM feedback f
        LEFT JOIN briefing_reports br ON br.id = f.briefing_report_id
        WHERE f.briefing_report_id IS NOT NULL
          AND br.id IS NULL
    ) THEN
        RAISE EXCEPTION 'V21 aborted: feedback.briefing_report_id orphan rows exist';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM feedback f
        LEFT JOIN chat_turns ct ON ct.id = f.chat_turn_id
        WHERE f.chat_turn_id IS NOT NULL
          AND ct.id IS NULL
    ) THEN
        RAISE EXCEPTION 'V21 aborted: feedback.chat_turn_id orphan rows exist';
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_feedback_card_news_id
    ON feedback (card_news_id);
CREATE INDEX IF NOT EXISTS idx_feedback_briefing_report_id
    ON feedback (briefing_report_id);
CREATE INDEX IF NOT EXISTS idx_feedback_chat_turn_id
    ON feedback (chat_turn_id);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_feedback_card_news'
          AND conrelid = 'feedback'::regclass
    ) THEN
        ALTER TABLE feedback
            ADD CONSTRAINT fk_feedback_card_news
            FOREIGN KEY (card_news_id)
            REFERENCES card_news(id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_feedback_briefing_report'
          AND conrelid = 'feedback'::regclass
    ) THEN
        ALTER TABLE feedback
            ADD CONSTRAINT fk_feedback_briefing_report
            FOREIGN KEY (briefing_report_id)
            REFERENCES briefing_reports(id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_feedback_chat_turn'
          AND conrelid = 'feedback'::regclass
    ) THEN
        ALTER TABLE feedback
            ADD CONSTRAINT fk_feedback_chat_turn
            FOREIGN KEY (chat_turn_id)
            REFERENCES chat_turns(id)
            ON DELETE SET NULL;
    END IF;
END $$;

-- ------------------------------------------------------------------
-- log relations.
-- ------------------------------------------------------------------
ALTER TABLE crawl_logs
    ADD COLUMN IF NOT EXISTS crawl_run_id UUID NULL;

ALTER TABLE pipeline_logs
    ADD COLUMN IF NOT EXISTS crawl_run_id UUID NULL;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM crawl_logs cl
        LEFT JOIN crawl_runs cr ON cr.id = cl.crawl_run_id
        WHERE cl.crawl_run_id IS NOT NULL
          AND cr.id IS NULL
    ) THEN
        RAISE EXCEPTION 'V21 aborted: crawl_logs.crawl_run_id orphan rows exist';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM pipeline_logs pl
        LEFT JOIN crawl_runs cr ON cr.id = pl.crawl_run_id
        WHERE pl.crawl_run_id IS NOT NULL
          AND cr.id IS NULL
    ) THEN
        RAISE EXCEPTION 'V21 aborted: pipeline_logs.crawl_run_id orphan rows exist';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM usage_logs ul
        LEFT JOIN pipeline_logs pl ON pl.id = ul.pipeline_log_id
        WHERE ul.pipeline_log_id IS NOT NULL
          AND pl.id IS NULL
    ) THEN
        RAISE EXCEPTION 'V21 aborted: usage_logs.pipeline_log_id orphan rows exist';
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_crawl_logs_crawl_run_id
    ON crawl_logs (crawl_run_id);
CREATE INDEX IF NOT EXISTS idx_pipeline_logs_crawl_run_id
    ON pipeline_logs (crawl_run_id);
CREATE INDEX IF NOT EXISTS idx_usage_logs_pipeline_log_id
    ON usage_logs (pipeline_log_id);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_crawl_logs_crawl_run'
          AND conrelid = 'crawl_logs'::regclass
    ) THEN
        ALTER TABLE crawl_logs
            ADD CONSTRAINT fk_crawl_logs_crawl_run
            FOREIGN KEY (crawl_run_id)
            REFERENCES crawl_runs(id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_pipeline_logs_crawl_run'
          AND conrelid = 'pipeline_logs'::regclass
    ) THEN
        ALTER TABLE pipeline_logs
            ADD CONSTRAINT fk_pipeline_logs_crawl_run
            FOREIGN KEY (crawl_run_id)
            REFERENCES crawl_runs(id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_usage_logs_pipeline_log'
          AND conrelid = 'usage_logs'::regclass
    ) THEN
        ALTER TABLE usage_logs
            ADD CONSTRAINT fk_usage_logs_pipeline_log
            FOREIGN KEY (pipeline_log_id)
            REFERENCES pipeline_logs(id)
            ON DELETE SET NULL;
    END IF;
END $$;

-- ------------------------------------------------------------------
-- weak_signal_cards typed links.
-- ------------------------------------------------------------------
ALTER TABLE weak_signal_cards
    ADD COLUMN IF NOT EXISTS source_raw_article_id BIGINT NULL,
    ADD COLUMN IF NOT EXISTS card_news_id VARCHAR(50) NULL;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM weak_signal_cards ws
        LEFT JOIN raw_articles ra ON ra.id = ws.source_raw_article_id
        WHERE ws.source_raw_article_id IS NOT NULL
          AND ra.id IS NULL
    ) THEN
        RAISE EXCEPTION 'V21 aborted: weak_signal_cards.source_raw_article_id orphan rows exist';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM weak_signal_cards ws
        LEFT JOIN card_news cn ON cn.id = ws.card_news_id
        WHERE ws.card_news_id IS NOT NULL
          AND cn.id IS NULL
    ) THEN
        RAISE EXCEPTION 'V21 aborted: weak_signal_cards.card_news_id orphan rows exist';
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_weak_signal_cards_source_raw_article_id
    ON weak_signal_cards (source_raw_article_id);
CREATE INDEX IF NOT EXISTS idx_weak_signal_cards_card_news_id
    ON weak_signal_cards (card_news_id);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_weak_signal_cards_source_raw_article'
          AND conrelid = 'weak_signal_cards'::regclass
    ) THEN
        ALTER TABLE weak_signal_cards
            ADD CONSTRAINT fk_weak_signal_cards_source_raw_article
            FOREIGN KEY (source_raw_article_id)
            REFERENCES raw_articles(id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_weak_signal_cards_card_news'
          AND conrelid = 'weak_signal_cards'::regclass
    ) THEN
        ALTER TABLE weak_signal_cards
            ADD CONSTRAINT fk_weak_signal_cards_card_news
            FOREIGN KEY (card_news_id)
            REFERENCES card_news(id)
            ON DELETE SET NULL;
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS weak_signal_card_articles (
    id BIGSERIAL PRIMARY KEY,
    weak_signal_card_id VARCHAR(40) NOT NULL,
    raw_article_id BIGINT NOT NULL,
    article_order INT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_weak_signal_card_articles_signal
        FOREIGN KEY (weak_signal_card_id)
        REFERENCES weak_signal_cards(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_weak_signal_card_articles_article
        FOREIGN KEY (raw_article_id)
        REFERENCES raw_articles(id)
        ON DELETE RESTRICT,

    CONSTRAINT uq_weak_signal_card_article
        UNIQUE (weak_signal_card_id, raw_article_id)
);

CREATE INDEX IF NOT EXISTS idx_weak_signal_card_articles_raw_article_id
    ON weak_signal_card_articles (raw_article_id);
CREATE INDEX IF NOT EXISTS idx_weak_signal_card_articles_signal_order
    ON weak_signal_card_articles (weak_signal_card_id, article_order);

-- ------------------------------------------------------------------
-- analysis_ledger normalized peer/card mappings. JSON arrays remain.
-- ------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS analysis_ledger_card_news (
    id BIGSERIAL PRIMARY KEY,
    analysis_ledger_id BIGINT NOT NULL,
    card_news_id VARCHAR(50) NOT NULL,
    card_order INT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_analysis_ledger_card_news_ledger
        FOREIGN KEY (analysis_ledger_id)
        REFERENCES analysis_ledger(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_analysis_ledger_card_news_card
        FOREIGN KEY (card_news_id)
        REFERENCES card_news(id)
        ON DELETE RESTRICT,

    CONSTRAINT uq_analysis_ledger_card_news
        UNIQUE (analysis_ledger_id, card_news_id)
);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM analysis_ledger al
        CROSS JOIN LATERAL jsonb_array_elements_text(al.source_card_ids) AS cid(card_news_id)
        WHERE jsonb_typeof(al.source_card_ids) = 'array'
          AND NOT EXISTS (
              SELECT 1 FROM card_news cn WHERE cn.id = cid.card_news_id
          )
    ) THEN
        RAISE EXCEPTION 'V21 aborted: analysis_ledger.source_card_ids orphan rows exist';
    END IF;
END $$;

INSERT INTO analysis_ledger_card_news (
    analysis_ledger_id,
    card_news_id,
    card_order
)
SELECT
    al.id,
    cid.card_news_id,
    cid.ordinality::int
FROM analysis_ledger al
CROSS JOIN LATERAL jsonb_array_elements_text(al.source_card_ids)
    WITH ORDINALITY AS cid(card_news_id, ordinality)
WHERE jsonb_typeof(al.source_card_ids) = 'array'
ON CONFLICT (analysis_ledger_id, card_news_id) DO NOTHING;

CREATE INDEX IF NOT EXISTS idx_analysis_ledger_card_news_card_news_id
    ON analysis_ledger_card_news (card_news_id);
CREATE INDEX IF NOT EXISTS idx_analysis_ledger_card_news_ledger_order
    ON analysis_ledger_card_news (analysis_ledger_id, card_order);

CREATE TABLE IF NOT EXISTS analysis_ledger_peer_companies (
    id BIGSERIAL PRIMARY KEY,
    analysis_ledger_id BIGINT NOT NULL,
    peer_company_id VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_analysis_ledger_peer_companies_ledger
        FOREIGN KEY (analysis_ledger_id)
        REFERENCES analysis_ledger(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_analysis_ledger_peer_companies_peer
        FOREIGN KEY (peer_company_id)
        REFERENCES peer_companies(id)
        ON DELETE RESTRICT,

    CONSTRAINT uq_analysis_ledger_peer_company
        UNIQUE (analysis_ledger_id, peer_company_id)
);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM analysis_ledger al
        CROSS JOIN LATERAL jsonb_array_elements_text(al.peer_ids) AS pid(peer_company_id)
        WHERE jsonb_typeof(al.peer_ids) = 'array'
          AND NOT EXISTS (
              SELECT 1 FROM peer_companies pc WHERE pc.id = pid.peer_company_id
          )
    ) THEN
        RAISE EXCEPTION 'V21 aborted: analysis_ledger.peer_ids orphan rows exist';
    END IF;
END $$;

INSERT INTO analysis_ledger_peer_companies (
    analysis_ledger_id,
    peer_company_id
)
SELECT
    al.id,
    pid.peer_company_id
FROM analysis_ledger al
CROSS JOIN LATERAL jsonb_array_elements_text(al.peer_ids) AS pid(peer_company_id)
WHERE jsonb_typeof(al.peer_ids) = 'array'
ON CONFLICT (analysis_ledger_id, peer_company_id) DO NOTHING;

CREATE INDEX IF NOT EXISTS idx_analysis_ledger_peer_companies_peer_company_id
    ON analysis_ledger_peer_companies (peer_company_id);
