-- ============================================================
-- V4 — 미사용 컬럼·중복 인덱스 정리
--
-- 코드 grep 으로 INSERT/SELECT 사용처가 0 건인 컬럼과
-- UNIQUE 제약 자동 인덱스와 겹치는 명시적 인덱스를 제거.
--
-- 동반 변경:
--   · axis-ai
--       - src/db/article_store.py update_classification : importance_level, qdrant_vector_id SET 제거
--       - src/rag/vector_index.py                       : qdrant_vector_id kwarg 제거
--       - src/agents/classification_agent.py            : importance 인자 제거
--   · axis-backend
--       - domain/ArticleImage.java                      : imageHash, licenseStatus 필드 제거
-- ============================================================


-- ─── raw_articles ──────────────────────────────────────────
-- importance_level (varchar) — UPDATE 만 있고 SELECT 0 건. importance_score 만 사용 중.
-- qdrant_vector_id (uuid)   — UPDATE 만 있고 SELECT 0 건. Qdrant payload 의 rdb_id 로 역참조 충분.
ALTER TABLE raw_articles DROP COLUMN IF EXISTS importance_level;
ALTER TABLE raw_articles DROP COLUMN IF EXISTS qdrant_vector_id;


-- ─── article_images ────────────────────────────────────────
-- image_hash (varchar 64)     — INSERT/SELECT 0 건. source_url_hash 가 UNIQUE 키 역할.
-- license_status (varchar 20) — 기본값 'unknown' 외 INSERT 없음, SELECT 0 건.
ALTER TABLE article_images DROP COLUMN IF EXISTS image_hash;
ALTER TABLE article_images DROP COLUMN IF EXISTS license_status;

-- image_hash 컬럼이 사라지면 idx_article_images_hash 도 자동 제거되지만 명시적으로.
DROP INDEX IF EXISTS idx_article_images_hash;


-- ─── 중복 인덱스 정리 ───────────────────────────────────────
-- raw_articles.url 은 UNIQUE — PG 가 자동 인덱스 생성 (raw_articles_url_key).
DROP INDEX IF EXISTS idx_raw_articles_url;

-- evidence_chain.issue_card_id 는 UNIQUE — PG 자동 인덱스와 중복.
DROP INDEX IF EXISTS idx_evidence_chain_card;
