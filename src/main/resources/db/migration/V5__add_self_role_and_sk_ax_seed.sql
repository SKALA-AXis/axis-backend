-- ============================================================
-- V5 — peer_companies 에 role 컬럼 추가 + SK AX (자사) 시드
--
-- 배경: MVP 단계에서 발주처 (SK AX) 가 내부 정보를 줄 수 없으니,
--       Peer 4사와 동일하게 외부 공개 정보 (뉴스·공시·채용공고) 를
--       크롤링하여 비교 baseline 으로 활용. 단, SK AX 자체의
--       issue_card 는 생성하지 않음 (자사가 자사에게 주는 시사점은
--       의미적으로 어색 — raw_articles 단계까지만 적재).
--
-- 도메인 의미:
--   role='peer'  → 모니터링 대상 경쟁사 (4사). issue_card 생성 O.
--   role='self'  → 자사 (SK AX). raw_articles 적재 O / issue_card X.
--
-- 동반 axis-ai 변경 (별도 PR):
--   · CrawlAgent — 크롤러 키워드에 SK AX 추가 (peer_id='sk_ax')
--   · ClassifyAgent / IssueCardAgent — peer_companies.role 조회 후
--                                        role='self' 면 카드 생성 skip
--   · ExposureScoreAgent — role='self' 인 경우 peer_mention_rate=0 강제
-- ============================================================


-- 1) role 컬럼 추가 (CHECK 제약으로 enum 강제)
ALTER TABLE peer_companies
    ADD COLUMN role VARCHAR(10) NOT NULL DEFAULT 'peer'
    CHECK (role IN ('peer', 'self'));


-- 2) SK AX 시드 — role='self'
INSERT INTO peer_companies (id, name, keywords, role) VALUES
    ('sk_ax', 'SK AX', ARRAY['SK AX', 'SKAX', '에스케이에이엑스', 'SK 에이엑스'], 'self')
ON CONFLICT (id) DO NOTHING;


-- 3) role 별 조회 인덱스 (필터링 빈번)
CREATE INDEX IF NOT EXISTS idx_peer_companies_role
    ON peer_companies (role);
