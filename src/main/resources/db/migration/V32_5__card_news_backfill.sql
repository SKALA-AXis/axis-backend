-- V32_5: card_news 후처리 회귀 백필 (P3-CRIT-4)
--
-- 배경:
--   2026-05-15 부터 일부 카드의 peer_company_id FK 가 NULL,
--   2026-05-20 부터 primary_keyword_category 도 NULL 인 회귀가 누적되었다.
--   원천 데이터 (card_news.company / raw_articles.matched_companies/matched_sectors)
--   는 모두 정상이라 단순 join 으로 즉시 복구 가능하다.
--
-- 회귀 원인 추적은 별도 P1 ticket (axis-ai CardNewsAgent 후처리 변경 이력) 에서
-- 진행하며, 본 SQL 은 idempotent (WHERE ... IS NULL 가드) 이므로 매일 재실행해도
-- 안전하다.
BEGIN;

-- (a) peer_company_id FK 백필 — card_news.company 가 peer_companies.id 와 일치하는 경우.
--     card_news 테이블은 ``updated_at`` 컬럼이 없으므로 set 절에는 FK 만 갱신한다.
UPDATE card_news cn
SET peer_company_id = cn.company
WHERE cn.peer_company_id IS NULL
  AND cn.company IS NOT NULL
  AND EXISTS (
      SELECT 1
      FROM peer_companies pc
      WHERE pc.id = cn.company
  );

-- (b) primary_keyword_category 백필 — raw_articles.matched_sectors 첫 원소 사용.
--     source_raw_article_ids 가 비어 있을 수도 있으나 (P3-CRIT-2 의 15%) 그 경우는
--     건너뛰고 다음 일자의 카드 INSERT 시점에 자연 회복되도록 둔다.
UPDATE card_news cn
SET primary_keyword_category = sub.sector
FROM (
    SELECT cn2.id AS card_id,
           (
               SELECT ra.matched_sectors->>0
               FROM raw_articles ra
               WHERE ra.id = ANY(cn2.source_raw_article_ids)
                 AND jsonb_array_length(ra.matched_sectors) > 0
               LIMIT 1
           ) AS sector
    FROM card_news cn2
    WHERE cn2.primary_keyword_category IS NULL
      AND cn2.source_raw_article_ids IS NOT NULL
      AND array_length(cn2.source_raw_article_ids, 1) > 0
) sub
WHERE cn.id = sub.card_id
  AND sub.sector IS NOT NULL;

-- (c) 검증 — 백필 후 NULL 비율 (Flyway out 로그에 남아 audit 가능).
DO $$
DECLARE
    fk_null_remaining INT;
    sector_null_remaining INT;
    rows_total INT;
BEGIN
    SELECT COUNT(*) FILTER (WHERE peer_company_id IS NULL),
           COUNT(*) FILTER (WHERE primary_keyword_category IS NULL),
           COUNT(*)
      INTO fk_null_remaining, sector_null_remaining, rows_total
      FROM card_news
     WHERE created_at >= '2026-05-15';

    RAISE NOTICE 'V32_5 backfill | rows_total=% fk_null_remaining=% sector_null_remaining=%',
        rows_total, fk_null_remaining, sector_null_remaining;
END $$;

COMMIT;
