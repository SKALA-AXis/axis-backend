-- V9: issue_cards 테이블을 card_news 로 rename (Frontend / OpenAPI CardNews 스키마와 명명 통일).
--
-- 변경 이유: frontend / api/openapi.yaml 의 `CardNews` 스키마가 SoT 인데 backend / ai 는
-- `issue_cards` (DB) + `IssueCard*` (Java) + `issue_card` (Python) 로 분기되어 있었음. 명명 일원화.
--
-- 안전성:
--   1. Postgres `ALTER TABLE ... RENAME` 는 인덱스·제약·트리거를 자동으로 따라옴 (ALTER 단일 atomic).
--   2. 본 migration 완료 후에도 axis-ai pod 중 일부는 잠시 `issue_cards` 이름으로 쿼리할 수 있음
--      (ArgoCD rollout 비동기). 따라서 view `issue_cards` 를 임시로 남겨 backward compatibility 보장.
--   3. 모든 service 가 새 이름으로 전환된 후 추후 V10 에서 view 를 drop.
--
-- 무엇이 같이 rename 되는가:
--   - PK index issue_cards_pkey → card_news_pkey (Postgres 자동)
--   - article_images.issue_card_id 컬럼은 card_news_id 로 함께 rename (일관성)
--   - issue_cards_*_idx + article_images_issue_card_*_idx 보조 인덱스도 명시적 rename

-- 1) 본 테이블 rename
ALTER TABLE issue_cards RENAME TO card_news;

-- 2) article_images 의 FK 컬럼 이름도 일관성 위해 rename
ALTER TABLE article_images RENAME COLUMN issue_card_id TO card_news_id;

-- 3) 보조 인덱스 명시적 rename (Postgres 가 자동으로 따라오지 않는 일부)
DO $$
DECLARE
    idx_name text;
BEGIN
    FOR idx_name IN
        SELECT indexname FROM pg_indexes
        WHERE schemaname = 'public'
          AND (
            (tablename = 'card_news' AND indexname LIKE 'issue_cards_%')
            OR (tablename = 'article_images' AND indexname LIKE 'article_images_issue_card_%')
          )
    LOOP
        EXECUTE format('ALTER INDEX %I RENAME TO %I',
                       idx_name,
                       replace(replace(idx_name, 'issue_cards_', 'card_news_'),
                               'article_images_issue_card_', 'article_images_card_news_'));
    END LOOP;
END $$;

-- 백워드 호환 VIEW — rollout window 동안 구 image 의 axis-ai pod 가 `issue_cards` 로 조회/삽입 가능.
-- 단순 SELECT * 라 Postgres 가 자동으로 updatable view 로 인식 → INSERT/UPDATE/DELETE 도 동작.
-- V10 에서 모든 pod 가 새 이름 사용 확인 후 DROP VIEW issue_cards 권장.
CREATE OR REPLACE VIEW issue_cards AS SELECT * FROM card_news;

COMMENT ON VIEW issue_cards IS '(DEPRECATED, V10 drop 대상) V9 의 RENAME 시점 임시 호환 VIEW. axis-ai 가 card_news 로 전환 완료 후 제거.';
