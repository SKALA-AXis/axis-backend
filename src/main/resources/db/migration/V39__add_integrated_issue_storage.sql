-- V39: storage for IssueIntegrationAgent output.
--
-- The integrated issue payload is kept as JSONB for replay/debug compatibility,
-- while the fields downstream agents query frequently are normalized into
-- scalar columns and side tables.

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";

CREATE TABLE IF NOT EXISTS integrated_issues (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),

    schema_version VARCHAR(40) NOT NULL DEFAULT 'integrated_issue_v3',
    issue_key VARCHAR(160) NOT NULL,

    cluster_id BIGINT,
    representative_raw_article_id BIGINT REFERENCES raw_articles(id) ON DELETE SET NULL,

    main_company VARCHAR(80),
    event_type VARCHAR(80),
    source_family VARCHAR(40),
    scope_type VARCHAR(40),

    is_valid BOOLEAN NOT NULL DEFAULT FALSE,
    confidence DOUBLE PRECISION,
    status VARCHAR(30) NOT NULL DEFAULT 'active',
    is_current BOOLEAN NOT NULL DEFAULT TRUE,
    superseded_by UUID REFERENCES integrated_issues(id) ON DELETE SET NULL,

    headline TEXT,
    one_line_summary TEXT,
    analyzed_source_ids BIGINT[] NOT NULL DEFAULT '{}',
    source_ids BIGINT[] NOT NULL DEFAULT '{}',
    sectors TEXT[] NOT NULL DEFAULT '{}',
    mentioned_peer_companies TEXT[] NOT NULL DEFAULT '{}',

    content_summary TEXT,
    content_detailed_explanation TEXT,
    content_has_content BOOLEAN NOT NULL DEFAULT FALSE,
    content_compression_method VARCHAR(80),
    content_basis_scope VARCHAR(80),
    content_source_count INT NOT NULL DEFAULT 0,
    content_section_count INT NOT NULL DEFAULT 0,

    issue_brief JSONB NOT NULL DEFAULT '{}'::jsonb,
    analysis_ready_inputs JSONB NOT NULL DEFAULT '{}'::jsonb,
    content_digest JSONB NOT NULL DEFAULT '{}'::jsonb,
    issue_frame JSONB NOT NULL DEFAULT '{}'::jsonb,
    sources JSONB NOT NULL DEFAULT '[]'::jsonb,
    evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
    quality JSONB NOT NULL DEFAULT '{}'::jsonb,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,

    payload_hash VARCHAR(64),
    global_search_text TEXT NOT NULL DEFAULT '',

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_integrated_issues_confidence
        CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
    CONSTRAINT chk_integrated_issues_status
        CHECK (status IN ('active', 'superseded', 'failed', 'review')),
    CONSTRAINT chk_integrated_issues_content_counts
        CHECK (content_source_count >= 0 AND content_section_count >= 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_integrated_issues_current_issue_key
    ON integrated_issues (schema_version, issue_key)
    WHERE is_current = TRUE;

CREATE INDEX IF NOT EXISTS idx_integrated_issues_cluster_current
    ON integrated_issues (cluster_id, is_current, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_integrated_issues_representative
    ON integrated_issues (representative_raw_article_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_integrated_issues_company_event
    ON integrated_issues (main_company, event_type, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_integrated_issues_valid_current
    ON integrated_issues (is_valid, is_current, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_integrated_issues_analyzed_sources
    ON integrated_issues USING GIN (analyzed_source_ids);
CREATE INDEX IF NOT EXISTS idx_integrated_issues_source_ids
    ON integrated_issues USING GIN (source_ids);
CREATE INDEX IF NOT EXISTS idx_integrated_issues_sectors
    ON integrated_issues USING GIN (sectors);
CREATE INDEX IF NOT EXISTS idx_integrated_issues_peer_companies
    ON integrated_issues USING GIN (mentioned_peer_companies);
CREATE INDEX IF NOT EXISTS idx_integrated_issues_payload_gin
    ON integrated_issues USING GIN (payload);
CREATE INDEX IF NOT EXISTS idx_integrated_issues_content_digest_gin
    ON integrated_issues USING GIN (content_digest);
CREATE INDEX IF NOT EXISTS idx_integrated_issues_issue_frame_gin
    ON integrated_issues USING GIN (issue_frame);
CREATE INDEX IF NOT EXISTS idx_integrated_issues_evidence_gin
    ON integrated_issues USING GIN (evidence);
CREATE INDEX IF NOT EXISTS idx_integrated_issues_global_search_trgm
    ON integrated_issues USING GIN (global_search_text gin_trgm_ops);

CREATE TABLE IF NOT EXISTS integrated_issue_source_articles (
    id BIGSERIAL PRIMARY KEY,
    integrated_issue_id UUID NOT NULL REFERENCES integrated_issues(id) ON DELETE CASCADE,
    raw_article_id BIGINT REFERENCES raw_articles(id) ON DELETE SET NULL,

    source_order INT NOT NULL DEFAULT 0,
    is_analyzed_basis BOOLEAN NOT NULL DEFAULT FALSE,

    title TEXT,
    source_name VARCHAR(100),
    source_type VARCHAR(50),
    publisher VARCHAR(150),
    published_at TIMESTAMPTZ,
    url TEXT,
    relevance_label VARCHAR(30),
    relevance_score DOUBLE PRECISION,

    source_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_integrated_issue_source_article
        UNIQUE (integrated_issue_id, raw_article_id),
    CONSTRAINT chk_integrated_issue_source_relevance_score
        CHECK (relevance_score IS NULL OR (relevance_score >= 0 AND relevance_score <= 1))
);

CREATE INDEX IF NOT EXISTS idx_integrated_issue_sources_issue_order
    ON integrated_issue_source_articles (integrated_issue_id, source_order);
CREATE INDEX IF NOT EXISTS idx_integrated_issue_sources_raw_article
    ON integrated_issue_source_articles (raw_article_id);
CREATE INDEX IF NOT EXISTS idx_integrated_issue_sources_basis
    ON integrated_issue_source_articles (integrated_issue_id, is_analyzed_basis);
CREATE INDEX IF NOT EXISTS idx_integrated_issue_sources_payload_gin
    ON integrated_issue_source_articles USING GIN (source_payload);

CREATE TABLE IF NOT EXISTS integrated_issue_content_sections (
    id BIGSERIAL PRIMARY KEY,
    integrated_issue_id UUID NOT NULL REFERENCES integrated_issues(id) ON DELETE CASCADE,

    section_key VARCHAR(80) NOT NULL,
    section_order INT NOT NULL DEFAULT 0,
    title TEXT,
    summary TEXT,
    details TEXT,
    key_points TEXT[] NOT NULL DEFAULT '{}',
    raw_article_ids BIGINT[] NOT NULL DEFAULT '{}',
    evidence_ref_ids TEXT[] NOT NULL DEFAULT '{}',

    section_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_integrated_issue_content_section
        UNIQUE (integrated_issue_id, section_key)
);

CREATE INDEX IF NOT EXISTS idx_integrated_issue_sections_issue_order
    ON integrated_issue_content_sections (integrated_issue_id, section_order);
CREATE INDEX IF NOT EXISTS idx_integrated_issue_sections_key
    ON integrated_issue_content_sections (section_key);
CREATE INDEX IF NOT EXISTS idx_integrated_issue_sections_raw_articles
    ON integrated_issue_content_sections USING GIN (raw_article_ids);
CREATE INDEX IF NOT EXISTS idx_integrated_issue_sections_evidence_refs
    ON integrated_issue_content_sections USING GIN (evidence_ref_ids);
CREATE INDEX IF NOT EXISTS idx_integrated_issue_sections_payload_gin
    ON integrated_issue_content_sections USING GIN (section_payload);

CREATE TABLE IF NOT EXISTS integrated_issue_evidence_references (
    id BIGSERIAL PRIMARY KEY,
    integrated_issue_id UUID NOT NULL REFERENCES integrated_issues(id) ON DELETE CASCADE,

    evidence_ref_id VARCHAR(40) NOT NULL,
    evidence_text TEXT NOT NULL,
    source_ids BIGINT[] NOT NULL DEFAULT '{}',

    reference_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_integrated_issue_evidence_ref
        UNIQUE (integrated_issue_id, evidence_ref_id)
);

CREATE INDEX IF NOT EXISTS idx_integrated_issue_evidence_issue
    ON integrated_issue_evidence_references (integrated_issue_id);
CREATE INDEX IF NOT EXISTS idx_integrated_issue_evidence_source_ids
    ON integrated_issue_evidence_references USING GIN (source_ids);
CREATE INDEX IF NOT EXISTS idx_integrated_issue_evidence_text_trgm
    ON integrated_issue_evidence_references USING GIN (evidence_text gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_integrated_issue_evidence_payload_gin
    ON integrated_issue_evidence_references USING GIN (reference_payload);

ALTER TABLE card_news
    ADD COLUMN IF NOT EXISTS integrated_issue_id UUID;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_card_news_integrated_issue'
          AND conrelid = 'card_news'::regclass
    ) THEN
        ALTER TABLE card_news
            ADD CONSTRAINT fk_card_news_integrated_issue
            FOREIGN KEY (integrated_issue_id)
            REFERENCES integrated_issues(id)
            ON DELETE SET NULL;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_card_news_integrated_issue
    ON card_news (integrated_issue_id)
    WHERE integrated_issue_id IS NOT NULL;

CREATE OR REPLACE FUNCTION axis_touch_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION axis_refresh_integrated_issue_global_search_text()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.global_search_text := concat_ws(
        ' ',
        NEW.headline,
        NEW.one_line_summary,
        NEW.main_company,
        NEW.event_type,
        NEW.source_family,
        NEW.scope_type,
        NEW.content_summary,
        NEW.content_detailed_explanation,
        array_to_string(NEW.sectors, ' '),
        array_to_string(NEW.mentioned_peer_companies, ' '),
        NEW.issue_frame::text,
        NEW.content_digest::text,
        NEW.evidence::text
    );
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_integrated_issues_updated_at ON integrated_issues;
CREATE TRIGGER trg_integrated_issues_updated_at
BEFORE UPDATE ON integrated_issues
FOR EACH ROW
EXECUTE FUNCTION axis_touch_updated_at();

DROP TRIGGER IF EXISTS trg_integrated_issues_global_search_text ON integrated_issues;
CREATE TRIGGER trg_integrated_issues_global_search_text
BEFORE INSERT OR UPDATE ON integrated_issues
FOR EACH ROW
EXECUTE FUNCTION axis_refresh_integrated_issue_global_search_text();

COMMENT ON TABLE integrated_issues IS
    'Canonical storage for IssueIntegrationAgent output. Keeps full payload plus queryable fields for downstream analysis agents.';
COMMENT ON COLUMN integrated_issues.issue_key IS
    'Idempotency key. Recommended values: cluster:{cluster_id}:v3 or raw:{raw_article_id}:v3.';
COMMENT ON COLUMN integrated_issues.representative_raw_article_id IS
    'raw_articles.id that was actually analyzed when a cluster has many source articles.';
COMMENT ON COLUMN integrated_issues.analyzed_source_ids IS
    'raw_articles.id list used as analytical basis, usually one representative source for clustered news.';
COMMENT ON COLUMN integrated_issues.source_ids IS
    'All raw_articles.id values retained as source/citation candidates.';
COMMENT ON COLUMN integrated_issues.payload IS
    'Full integrated_issue_v3 JSON object returned by IssueIntegrationAgent.';

COMMENT ON TABLE integrated_issue_source_articles IS
    'Normalized source list for an integrated issue. Mirrors the public sources array with raw_articles FK support.';
COMMENT ON COLUMN integrated_issue_source_articles.is_analyzed_basis IS
    'True when the article was actually analyzed, not only retained as a citation/source candidate.';

COMMENT ON TABLE integrated_issue_content_sections IS
    'Normalized content_digest.sections for section-level retrieval by Summary, Analysis, CardNews, Mixer, and KeywordGraph agents.';
COMMENT ON TABLE integrated_issue_evidence_references IS
    'Deduplicated evidence.references text store. Facts and frames can point here by evidence_ref_id.';
COMMENT ON COLUMN card_news.integrated_issue_id IS
    'IntegratedIssue payload used as the factual input for CardNewsAgent.';
