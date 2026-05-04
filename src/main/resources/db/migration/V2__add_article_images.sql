-- ============================================================
-- V2 — article_images 테이블 추가
--
-- 카드 뉴스에 들어갈 대표 이미지를 저장한다.
--   · 이미지 파일 자체는 공유 볼륨(IMAGE_STORAGE_PATH)에 저장
--   · DB는 메타데이터 + 상대 경로(storage_path)만 보관
--
-- 작성 주체
--   · INSERT/UPDATE: axis-ai (ImageFetchAgent — 별도 PR 예정)
--   · SELECT/파일 read: axis-backend (ImageController)
-- ============================================================

CREATE TABLE IF NOT EXISTS article_images (
    id                  BIGSERIAL    PRIMARY KEY,

    -- 출처 추적
    article_id          BIGINT       REFERENCES raw_articles(id) ON DELETE SET NULL,
    cluster_id          BIGINT,
    issue_card_id       VARCHAR(50)  REFERENCES issue_cards(id) ON DELETE SET NULL,

    -- 원본 URL
    source_url          TEXT         NOT NULL,
    source_url_hash     VARCHAR(64)  NOT NULL UNIQUE,    -- SHA-256(source_url) — 중복 다운로드 차단

    -- 파일 위치 (IMAGE_STORAGE_PATH 기준 상대 경로)
    --   예: 'lg_cns/2026-04/<sha256>.jpg'
    storage_path        TEXT         NOT NULL,
    content_type        VARCHAR(50),                     -- image/jpeg | image/png | image/webp
    width               INT,
    height              INT,
    file_size_bytes     INT,
    image_hash          VARCHAR(64),                     -- SHA-256(파일 콘텐츠) — 동일 파일 다른 URL 검출

    -- 표시 메타
    alt_text            TEXT,
    attribution         TEXT,                            -- 예: "제공: 한경"
    license_status      VARCHAR(20)  DEFAULT 'unknown',  -- unknown | attributed | public_domain | unsafe

    fetched_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_article_images_card
    ON article_images (issue_card_id);
CREATE INDEX IF NOT EXISTS idx_article_images_cluster
    ON article_images (cluster_id);
CREATE INDEX IF NOT EXISTS idx_article_images_hash
    ON article_images (image_hash);
