-- ============================================================
-- V7 — axis-ai current write schema alignment
--
-- 배경:
--   axis-infra/db/schema.sql 을 axis-ai 의 현재 저장 경로에 맞춰 갱신하면서,
--   Flyway 이력에도 동일한 변경을 남긴다.
--
-- 핵심 변경:
--   · raw_articles: crawler/article_store.py INSERT 필드(source_type, company, url_hash 등)에 맞춤
--   · issue_cards: peer_id 대신 company 문자열 기준으로 저장
--   · pipeline_logs: peer_id 대신 company 컬럼 사용
--   · evidence_chain: issue_card_id 를 PK 로 사용하는 axis-ai 저장 구조에 맞춤
--
-- 주의:
--   backend Java 엔티티/Repository 의 peerId 기반 조회는 별도 애플리케이션 코드 변경이 필요하다.
-- ============================================================

-- raw_articles
DROP INDEX IF EXISTS idx_raw_articles_peer_published;
DROP INDEX IF EXISTS idx_raw_articles_status;
DROP INDEX IF EXISTS idx_raw_articles_cluster;

ALTER TABLE raw_articles
    ADD COLUMN IF NOT EXISTS source_type VARCHAR(50),
    ADD COLUMN IF NOT EXISTS publisher VARCHAR(150),
    ADD COLUMN IF NOT EXISTS url_hash VARCHAR(32),
    ADD COLUMN IF NOT EXISTS company JSONB DEFAULT '[]',
    ADD COLUMN IF NOT EXISTS language VARCHAR(10) DEFAULT 'ko',
    ADD COLUMN IF NOT EXISTS content_type VARCHAR(30) DEFAULT 'news',
    ADD COLUMN IF NOT EXISTS crawl_status VARCHAR(20) DEFAULT 'success',
    ADD COLUMN IF NOT EXISTS error_message TEXT,
    ADD COLUMN IF NOT EXISTS relevance_score FLOAT,
    ADD COLUMN IF NOT EXISTS relevance_label VARCHAR(20),
    ADD COLUMN IF NOT EXISTS relevance_reason TEXT,
    ADD COLUMN IF NOT EXISTS matched_companies JSONB DEFAULT '[]',
    ADD COLUMN IF NOT EXISTS matched_sectors JSONB DEFAULT '[]',
    ADD COLUMN IF NOT EXISTS importance_level VARCHAR(20),
    ADD COLUMN IF NOT EXISTS qdrant_vector_id UUID;

UPDATE raw_articles
SET
    source_type = COALESCE(source_type, 'news'),
    url_hash = COALESCE(url_hash, md5(url)),
    company = CASE
        WHEN company IS NULL OR jsonb_typeof(company) <> 'array' THEN '[]'::jsonb
        ELSE company
    END,
    language = COALESCE(language, 'ko'),
    content_type = COALESCE(content_type, 'news'),
    crawl_status = COALESCE(crawl_status, 'success'),
    processing_status = COALESCE(processing_status, 'RAW'),
    metadata = COALESCE(metadata, '{}'::jsonb),
    matched_companies = COALESCE(matched_companies, '[]'::jsonb),
    matched_sectors = COALESCE(matched_sectors, '[]'::jsonb),
    collected_at = COALESCE(collected_at, NOW());

ALTER TABLE raw_articles
    ALTER COLUMN source_type SET NOT NULL,
    ALTER COLUMN url_hash SET NOT NULL,
    ALTER COLUMN published_at DROP NOT NULL,
    ALTER COLUMN collected_at SET NOT NULL,
    ALTER COLUMN company SET NOT NULL,
    ALTER COLUMN language SET NOT NULL,
    ALTER COLUMN content_type SET NOT NULL,
    ALTER COLUMN crawl_status SET NOT NULL,
    ALTER COLUMN processing_status TYPE VARCHAR(40),
    ALTER COLUMN processing_status SET NOT NULL,
    ALTER COLUMN metadata SET NOT NULL,
    ALTER COLUMN matched_companies SET NOT NULL,
    ALTER COLUMN matched_sectors SET NOT NULL,
    ALTER COLUMN title TYPE VARCHAR(500) USING LEFT(title, 500),
    ALTER COLUMN is_representative DROP DEFAULT;

ALTER TABLE raw_articles DROP COLUMN IF EXISTS source_tier;
ALTER TABLE raw_articles DROP COLUMN IF EXISTS peer_id;

CREATE INDEX IF NOT EXISTS idx_raw_articles_url_hash
    ON raw_articles (url_hash);
CREATE INDEX IF NOT EXISTS idx_raw_articles_source_type
    ON raw_articles (source_type);
CREATE INDEX IF NOT EXISTS idx_raw_articles_source_name
    ON raw_articles (source_name);
CREATE INDEX IF NOT EXISTS idx_raw_articles_published_at
    ON raw_articles (published_at);
CREATE INDEX IF NOT EXISTS idx_raw_articles_processing_status
    ON raw_articles (processing_status);
CREATE INDEX IF NOT EXISTS idx_raw_articles_company
    ON raw_articles USING GIN(company);
CREATE INDEX IF NOT EXISTS idx_raw_articles_metadata
    ON raw_articles USING GIN(metadata);
CREATE INDEX IF NOT EXISTS idx_raw_articles_matched_companies
    ON raw_articles USING GIN(matched_companies);
CREATE INDEX IF NOT EXISTS idx_raw_articles_matched_sectors
    ON raw_articles USING GIN(matched_sectors);
CREATE INDEX IF NOT EXISTS idx_raw_articles_cluster_id
    ON raw_articles (cluster_id);
CREATE INDEX IF NOT EXISTS idx_raw_articles_is_representative
    ON raw_articles (is_representative);
CREATE INDEX IF NOT EXISTS idx_raw_articles_qdrant_vector_id
    ON raw_articles (qdrant_vector_id);

-- issue_cards
DROP INDEX IF EXISTS idx_issue_cards_peer_created;

ALTER TABLE issue_cards
    ADD COLUMN IF NOT EXISTS company VARCHAR(50),
    ALTER COLUMN summary_lines SET DEFAULT '{}',
    ALTER COLUMN event_type SET DEFAULT 'tech',
    ALTER COLUMN importance SET DEFAULT 'low',
    ALTER COLUMN importance_score SET DEFAULT 0.0,
    ALTER COLUMN implication SET DEFAULT '{}',
    ALTER COLUMN sources SET DEFAULT '[]',
    ALTER COLUMN validation_pass SET DEFAULT FALSE,
    ALTER COLUMN validation_sc_score SET DEFAULT 0.0;

UPDATE issue_cards
SET
    company = COALESCE(company, peer_id),
    summary_lines = COALESCE(summary_lines, ARRAY[]::TEXT[]),
    event_type = COALESCE(event_type, 'tech'),
    importance = COALESCE(importance, 'low'),
    importance_score = COALESCE(importance_score, 0.0),
    implication = COALESCE(implication, '{}'::jsonb),
    sources = COALESCE(sources, '[]'::jsonb),
    validation_pass = COALESCE(validation_pass, FALSE),
    validation_sc_score = COALESCE(validation_sc_score, 0.0);

ALTER TABLE issue_cards
    ALTER COLUMN company SET NOT NULL,
    ALTER COLUMN title TYPE VARCHAR(500) USING LEFT(title, 500),
    ALTER COLUMN summary_lines SET NOT NULL,
    ALTER COLUMN event_type SET NOT NULL,
    ALTER COLUMN importance SET NOT NULL,
    ALTER COLUMN importance_score SET NOT NULL,
    ALTER COLUMN implication SET NOT NULL,
    ALTER COLUMN sources SET NOT NULL,
    ALTER COLUMN validation_pass SET NOT NULL,
    ALTER COLUMN validation_sc_score SET NOT NULL;

ALTER TABLE issue_cards DROP COLUMN IF EXISTS peer_id;

CREATE INDEX IF NOT EXISTS idx_issue_cards_company
    ON issue_cards (company);
CREATE INDEX IF NOT EXISTS idx_issue_cards_cluster_id
    ON issue_cards (cluster_id);
CREATE INDEX IF NOT EXISTS idx_issue_cards_validation_pass
    ON issue_cards (validation_pass);

-- pipeline_logs
ALTER TABLE pipeline_logs
    ADD COLUMN IF NOT EXISTS company VARCHAR(50),
    ALTER COLUMN input_count SET DEFAULT 0,
    ALTER COLUMN output_count SET DEFAULT 0,
    ALTER COLUMN elapsed_ms SET DEFAULT 0,
    ALTER COLUMN llm_tokens_used SET DEFAULT 0;

UPDATE pipeline_logs
SET
    company = COALESCE(company, peer_id),
    input_count = COALESCE(input_count, 0),
    output_count = COALESCE(output_count, 0),
    elapsed_ms = COALESCE(elapsed_ms, 0),
    llm_tokens_used = COALESCE(llm_tokens_used, 0);

ALTER TABLE pipeline_logs
    ALTER COLUMN input_count SET NOT NULL,
    ALTER COLUMN output_count SET NOT NULL,
    ALTER COLUMN elapsed_ms SET NOT NULL,
    ALTER COLUMN llm_tokens_used SET NOT NULL;

ALTER TABLE pipeline_logs DROP COLUMN IF EXISTS peer_id;

CREATE INDEX IF NOT EXISTS idx_pipeline_logs_step
    ON pipeline_logs (pipeline_step);
CREATE INDEX IF NOT EXISTS idx_pipeline_logs_company
    ON pipeline_logs (company);

-- evidence_chain
ALTER TABLE evidence_chain DROP CONSTRAINT IF EXISTS evidence_chain_pkey;
ALTER TABLE evidence_chain DROP CONSTRAINT IF EXISTS evidence_chain_issue_card_id_key;

ALTER TABLE evidence_chain
    ADD COLUMN IF NOT EXISTS financial_link JSONB DEFAULT '{}';

UPDATE evidence_chain
SET
    source_links = COALESCE(source_links, '[]'::jsonb),
    provenance = COALESCE(provenance, '{}'::jsonb),
    financial_refs = COALESCE(financial_refs, '[]'::jsonb),
    mbb_refs = COALESCE(mbb_refs, '[]'::jsonb),
    financial_link = COALESCE(financial_link, '{}'::jsonb),
    evidence_version = COALESCE(evidence_version, 'v3.0'),
    pass = COALESCE(pass, FALSE),
    missing = COALESCE(missing, ARRAY[]::TEXT[]);

ALTER TABLE evidence_chain
    ALTER COLUMN source_links SET NOT NULL,
    ALTER COLUMN provenance SET NOT NULL,
    ALTER COLUMN financial_refs SET NOT NULL,
    ALTER COLUMN mbb_refs SET NOT NULL,
    ALTER COLUMN financial_link SET NOT NULL,
    ALTER COLUMN evidence_version SET NOT NULL,
    ALTER COLUMN pass SET NOT NULL,
    ALTER COLUMN missing SET NOT NULL;

ALTER TABLE evidence_chain DROP COLUMN IF EXISTS id;
ALTER TABLE evidence_chain ADD PRIMARY KEY (issue_card_id);

CREATE INDEX IF NOT EXISTS idx_evidence_chain_pass
    ON evidence_chain (pass);
CREATE INDEX IF NOT EXISTS idx_evidence_chain_version
    ON evidence_chain (evidence_version);
