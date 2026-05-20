-- V32: add enforced anchor foreign keys for card-news-based product outputs.
--
-- Mixer, briefing, and insight rows can reference many cards through the
-- existing array read-model columns. PostgreSQL cannot enforce a standard FK
-- on each array element without reintroducing mapping tables, so this migration
-- adds nullable scalar "primary" anchors that are backed by real FKs while the
-- arrays remain the full multi-card source lists.
--
-- Excluded by design: global_industry_trends, legacy_records,
-- flyway_schema_history, and crawl_cursors.

CREATE OR REPLACE FUNCTION axis_first_existing_raw_article_id(candidate_ids BIGINT[])
RETURNS BIGINT
LANGUAGE sql
STABLE
AS $$
    SELECT ra.id
    FROM unnest(COALESCE(candidate_ids, '{}'::bigint[]))
        WITH ORDINALITY AS candidate(raw_article_id, sort_order)
    JOIN raw_articles ra
        ON ra.id = candidate.raw_article_id
    ORDER BY candidate.sort_order
    LIMIT 1;
$$;

CREATE OR REPLACE FUNCTION axis_first_existing_card_news_id(candidate_ids TEXT[])
RETURNS VARCHAR(50)
LANGUAGE sql
STABLE
AS $$
    SELECT cn.id
    FROM unnest(COALESCE(candidate_ids, '{}'::text[]))
        WITH ORDINALITY AS candidate(card_news_id, sort_order)
    JOIN card_news cn
        ON cn.id = candidate.card_news_id
    ORDER BY candidate.sort_order
    LIMIT 1;
$$;

CREATE OR REPLACE FUNCTION axis_first_existing_peer_company_id(
    candidate_peer_ids TEXT[],
    candidate_card_news_ids TEXT[]
)
RETURNS VARCHAR(50)
LANGUAGE sql
STABLE
AS $$
    WITH candidates AS (
        SELECT
            candidate.peer_company_id,
            0 AS source_order,
            candidate.sort_order
        FROM unnest(COALESCE(candidate_peer_ids, '{}'::text[]))
            WITH ORDINALITY AS candidate(peer_company_id, sort_order)
        WHERE candidate.peer_company_id IS NOT NULL
          AND candidate.peer_company_id <> ''

        UNION ALL

        SELECT
            COALESCE(cn.peer_company_id, cn.company) AS peer_company_id,
            1 AS source_order,
            candidate.sort_order
        FROM unnest(COALESCE(candidate_card_news_ids, '{}'::text[]))
            WITH ORDINALITY AS candidate(card_news_id, sort_order)
        JOIN card_news cn
            ON cn.id = candidate.card_news_id
        WHERE COALESCE(cn.peer_company_id, cn.company) IS NOT NULL
          AND COALESCE(cn.peer_company_id, cn.company) <> ''
    )
    SELECT pc.id
    FROM candidates
    JOIN peer_companies pc
        ON pc.id = candidates.peer_company_id
    ORDER BY candidates.source_order, candidates.sort_order
    LIMIT 1;
$$;

ALTER TABLE card_news
    ADD COLUMN IF NOT EXISTS primary_raw_article_id BIGINT;

ALTER TABLE briefing_reports
    ADD COLUMN IF NOT EXISTS primary_card_news_id VARCHAR(50),
    ADD COLUMN IF NOT EXISTS primary_peer_company_id VARCHAR(50);

ALTER TABLE mixer_results
    ADD COLUMN IF NOT EXISTS primary_card_news_id VARCHAR(50),
    ADD COLUMN IF NOT EXISTS primary_peer_company_id VARCHAR(50);

ALTER TABLE insight_reports
    ADD COLUMN IF NOT EXISTS primary_card_news_id VARCHAR(50),
    ADD COLUMN IF NOT EXISTS primary_peer_company_id VARCHAR(50);

UPDATE card_news cn
SET primary_raw_article_id = axis_first_existing_raw_article_id(cn.source_raw_article_ids)
WHERE cn.primary_raw_article_id IS NULL
  AND cardinality(COALESCE(cn.source_raw_article_ids, '{}'::bigint[])) > 0;

UPDATE briefing_reports br
SET
    primary_card_news_id = COALESCE(
        br.primary_card_news_id,
        axis_first_existing_card_news_id(br.related_card_ids)
    ),
    primary_peer_company_id = COALESCE(
        br.primary_peer_company_id,
        axis_first_existing_peer_company_id('{}'::text[], br.related_card_ids)
    )
WHERE br.primary_card_news_id IS NULL
   OR br.primary_peer_company_id IS NULL;

UPDATE mixer_results mr
SET
    primary_card_news_id = COALESCE(
        mr.primary_card_news_id,
        axis_first_existing_card_news_id(mr.input_card_ids)
    ),
    primary_peer_company_id = COALESCE(
        mr.primary_peer_company_id,
        axis_first_existing_peer_company_id(mr.input_peer_ids, mr.input_card_ids)
    )
WHERE mr.primary_card_news_id IS NULL
   OR mr.primary_peer_company_id IS NULL;

UPDATE insight_reports ir
SET
    primary_card_news_id = COALESCE(
        ir.primary_card_news_id,
        axis_first_existing_card_news_id(
            COALESCE(ir.source_card_ids, '{}'::text[])
            || COALESCE(ir.focus_card_ids, '{}'::text[])
        )
    ),
    primary_peer_company_id = COALESCE(
        ir.primary_peer_company_id,
        axis_first_existing_peer_company_id(
            ir.focus_peer_ids,
            COALESCE(ir.source_card_ids, '{}'::text[])
            || COALESCE(ir.focus_card_ids, '{}'::text[])
        )
    )
WHERE ir.primary_card_news_id IS NULL
   OR ir.primary_peer_company_id IS NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_card_news_primary_raw_article'
          AND conrelid = 'card_news'::regclass
    ) THEN
        ALTER TABLE card_news
            ADD CONSTRAINT fk_card_news_primary_raw_article
            FOREIGN KEY (primary_raw_article_id)
            REFERENCES raw_articles(id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_briefing_reports_primary_card_news'
          AND conrelid = 'briefing_reports'::regclass
    ) THEN
        ALTER TABLE briefing_reports
            ADD CONSTRAINT fk_briefing_reports_primary_card_news
            FOREIGN KEY (primary_card_news_id)
            REFERENCES card_news(id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_briefing_reports_primary_peer_company'
          AND conrelid = 'briefing_reports'::regclass
    ) THEN
        ALTER TABLE briefing_reports
            ADD CONSTRAINT fk_briefing_reports_primary_peer_company
            FOREIGN KEY (primary_peer_company_id)
            REFERENCES peer_companies(id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_mixer_results_primary_card_news'
          AND conrelid = 'mixer_results'::regclass
    ) THEN
        ALTER TABLE mixer_results
            ADD CONSTRAINT fk_mixer_results_primary_card_news
            FOREIGN KEY (primary_card_news_id)
            REFERENCES card_news(id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_mixer_results_primary_peer_company'
          AND conrelid = 'mixer_results'::regclass
    ) THEN
        ALTER TABLE mixer_results
            ADD CONSTRAINT fk_mixer_results_primary_peer_company
            FOREIGN KEY (primary_peer_company_id)
            REFERENCES peer_companies(id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_insight_reports_primary_card_news'
          AND conrelid = 'insight_reports'::regclass
    ) THEN
        ALTER TABLE insight_reports
            ADD CONSTRAINT fk_insight_reports_primary_card_news
            FOREIGN KEY (primary_card_news_id)
            REFERENCES card_news(id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_insight_reports_primary_peer_company'
          AND conrelid = 'insight_reports'::regclass
    ) THEN
        ALTER TABLE insight_reports
            ADD CONSTRAINT fk_insight_reports_primary_peer_company
            FOREIGN KEY (primary_peer_company_id)
            REFERENCES peer_companies(id)
            ON DELETE SET NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_card_news_primary_raw_article_in_sources'
          AND conrelid = 'card_news'::regclass
    ) THEN
        ALTER TABLE card_news
            ADD CONSTRAINT chk_card_news_primary_raw_article_in_sources
            CHECK (
                primary_raw_article_id IS NULL
                OR cardinality(COALESCE(source_raw_article_ids, '{}'::bigint[])) = 0
                OR primary_raw_article_id = ANY(source_raw_article_ids)
            );
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_briefing_reports_primary_card_in_related'
          AND conrelid = 'briefing_reports'::regclass
    ) THEN
        ALTER TABLE briefing_reports
            ADD CONSTRAINT chk_briefing_reports_primary_card_in_related
            CHECK (
                primary_card_news_id IS NULL
                OR cardinality(COALESCE(related_card_ids, '{}'::text[])) = 0
                OR primary_card_news_id = ANY(related_card_ids)
            );
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_mixer_results_primary_card_in_inputs'
          AND conrelid = 'mixer_results'::regclass
    ) THEN
        ALTER TABLE mixer_results
            ADD CONSTRAINT chk_mixer_results_primary_card_in_inputs
            CHECK (
                primary_card_news_id IS NULL
                OR cardinality(COALESCE(input_card_ids, '{}'::text[])) = 0
                OR primary_card_news_id = ANY(input_card_ids)
            );
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_insight_reports_primary_card_in_sources'
          AND conrelid = 'insight_reports'::regclass
    ) THEN
        ALTER TABLE insight_reports
            ADD CONSTRAINT chk_insight_reports_primary_card_in_sources
            CHECK (
                primary_card_news_id IS NULL
                OR (
                    cardinality(COALESCE(source_card_ids, '{}'::text[]))
                    + cardinality(COALESCE(focus_card_ids, '{}'::text[]))
                ) = 0
                OR primary_card_news_id = ANY(source_card_ids)
                OR primary_card_news_id = ANY(focus_card_ids)
            );
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_card_news_primary_raw_article
    ON card_news (primary_raw_article_id)
    WHERE primary_raw_article_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_briefing_reports_primary_card_news
    ON briefing_reports (primary_card_news_id)
    WHERE primary_card_news_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_briefing_reports_primary_peer_company
    ON briefing_reports (primary_peer_company_id)
    WHERE primary_peer_company_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_mixer_results_primary_card_news
    ON mixer_results (primary_card_news_id)
    WHERE primary_card_news_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_mixer_results_primary_peer_company
    ON mixer_results (primary_peer_company_id)
    WHERE primary_peer_company_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_insight_reports_primary_card_news
    ON insight_reports (primary_card_news_id)
    WHERE primary_card_news_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_insight_reports_primary_peer_company
    ON insight_reports (primary_peer_company_id)
    WHERE primary_peer_company_id IS NOT NULL;

CREATE OR REPLACE FUNCTION axis_set_card_news_primary_refs()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.primary_raw_article_id IS NULL
       OR (
            cardinality(COALESCE(NEW.source_raw_article_ids, '{}'::bigint[])) > 0
            AND NOT NEW.primary_raw_article_id = ANY(NEW.source_raw_article_ids)
       ) THEN
        NEW.primary_raw_article_id := axis_first_existing_raw_article_id(NEW.source_raw_article_ids);
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION axis_set_briefing_report_primary_refs()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.primary_card_news_id IS NULL
       OR (
            cardinality(COALESCE(NEW.related_card_ids, '{}'::text[])) > 0
            AND NOT NEW.primary_card_news_id = ANY(NEW.related_card_ids)
       ) THEN
        NEW.primary_card_news_id := axis_first_existing_card_news_id(NEW.related_card_ids);
    END IF;

    IF NEW.primary_peer_company_id IS NULL THEN
        NEW.primary_peer_company_id :=
            axis_first_existing_peer_company_id('{}'::text[], NEW.related_card_ids);
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION axis_set_mixer_result_primary_refs()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.primary_card_news_id IS NULL
       OR (
            cardinality(COALESCE(NEW.input_card_ids, '{}'::text[])) > 0
            AND NOT NEW.primary_card_news_id = ANY(NEW.input_card_ids)
       ) THEN
        NEW.primary_card_news_id := axis_first_existing_card_news_id(NEW.input_card_ids);
    END IF;

    IF NEW.primary_peer_company_id IS NULL
       OR (
            cardinality(COALESCE(NEW.input_peer_ids, '{}'::text[])) > 0
            AND NOT NEW.primary_peer_company_id = ANY(NEW.input_peer_ids)
       ) THEN
        NEW.primary_peer_company_id :=
            axis_first_existing_peer_company_id(NEW.input_peer_ids, NEW.input_card_ids);
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION axis_set_insight_report_primary_refs()
RETURNS TRIGGER AS $$
DECLARE
    candidate_card_ids TEXT[];
BEGIN
    candidate_card_ids :=
        COALESCE(NEW.source_card_ids, '{}'::text[])
        || COALESCE(NEW.focus_card_ids, '{}'::text[]);

    IF NEW.primary_card_news_id IS NULL
       OR (
            cardinality(candidate_card_ids) > 0
            AND NOT NEW.primary_card_news_id = ANY(candidate_card_ids)
       ) THEN
        NEW.primary_card_news_id := axis_first_existing_card_news_id(candidate_card_ids);
    END IF;

    IF NEW.primary_peer_company_id IS NULL
       OR (
            cardinality(COALESCE(NEW.focus_peer_ids, '{}'::text[])) > 0
            AND NOT NEW.primary_peer_company_id = ANY(NEW.focus_peer_ids)
       ) THEN
        NEW.primary_peer_company_id :=
            axis_first_existing_peer_company_id(NEW.focus_peer_ids, candidate_card_ids);
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_card_news_primary_refs ON card_news;
CREATE TRIGGER trg_card_news_primary_refs
BEFORE INSERT OR UPDATE OF source_raw_article_ids, primary_raw_article_id ON card_news
FOR EACH ROW
EXECUTE FUNCTION axis_set_card_news_primary_refs();

DROP TRIGGER IF EXISTS trg_briefing_reports_primary_refs ON briefing_reports;
CREATE TRIGGER trg_briefing_reports_primary_refs
BEFORE INSERT OR UPDATE OF related_card_ids, primary_card_news_id, primary_peer_company_id
ON briefing_reports
FOR EACH ROW
EXECUTE FUNCTION axis_set_briefing_report_primary_refs();

DROP TRIGGER IF EXISTS trg_mixer_results_primary_refs ON mixer_results;
CREATE TRIGGER trg_mixer_results_primary_refs
BEFORE INSERT OR UPDATE OF input_card_ids, input_peer_ids, primary_card_news_id, primary_peer_company_id
ON mixer_results
FOR EACH ROW
EXECUTE FUNCTION axis_set_mixer_result_primary_refs();

DROP TRIGGER IF EXISTS trg_insight_reports_primary_refs ON insight_reports;
CREATE TRIGGER trg_insight_reports_primary_refs
BEFORE INSERT OR UPDATE OF source_card_ids, focus_card_ids, focus_peer_ids, primary_card_news_id, primary_peer_company_id
ON insight_reports
FOR EACH ROW
EXECUTE FUNCTION axis_set_insight_report_primary_refs();

COMMENT ON COLUMN card_news.primary_raw_article_id IS
    'Primary source raw article anchor for FK traversal; source_raw_article_ids remains the full source list.';
COMMENT ON COLUMN briefing_reports.primary_card_news_id IS
    'Representative card_news FK for the briefing; related_card_ids remains the full period card set.';
COMMENT ON COLUMN briefing_reports.primary_peer_company_id IS
    'Representative peer_company FK derived from related cards when possible.';
COMMENT ON COLUMN mixer_results.primary_card_news_id IS
    'Representative card_news FK for the mixer result; input_card_ids remains the full mixed card set.';
COMMENT ON COLUMN mixer_results.primary_peer_company_id IS
    'Representative peer_company FK derived from input peers/cards when possible.';
COMMENT ON COLUMN insight_reports.primary_card_news_id IS
    'Representative card_news FK for the insight report; source/focus card arrays remain the full evidence set.';
COMMENT ON COLUMN insight_reports.primary_peer_company_id IS
    'Representative peer_company FK derived from focus peers/cards when possible.';
