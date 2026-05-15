-- V9: issue_cards 테이블을 card_news 로 rename (Frontend / OpenAPI CardNews 스키마와 명명 통일).
--
-- 변경 이유: frontend / api/openapi.yaml 의 `CardNews` 스키마가 SoT 인데 backend / ai 는
-- `issue_cards` (DB) + `IssueCard*` (Java) + `issue_card` (Python) 로 분기되어 있었음. 명명 일원화.
--
-- 안전성 (rollout race 회피):
--   1. **테이블만 rename**. `article_images.issue_card_id`, `evidence_chain.issue_card_id` 컬럼은
--      그대로 유지. 컬럼 rename 은 V10 으로 분리 — 모든 pod 가 새 image 로 안정화된 후 적용.
--   2. Postgres 의 view `issue_cards` 를 임시로 생성 → ArgoCD rollout 비동기 window 에서 axis-ai
--      구 image 의 `INSERT INTO issue_cards (...)` / `SELECT FROM issue_cards` 가 그대로 동작.
--      Postgres 는 simple SELECT * view 를 자동으로 updatable view 로 인식.
--   3. 컬럼 rename 을 V9 에 묶을 경우: V9 적용 직후 ~5분간 axis-ai 구 image 가
--      `INSERT INTO evidence_chain (issue_card_id, ...)` → column-not-found 로 실패 (view 가
--      column alias 기능을 제공하지 않으므로). 이 race 를 피하기 위해 컬럼은 V9 범위 밖.
--
-- V10 이후 cleanup (별도 PR):
--   - ALTER TABLE article_images RENAME COLUMN issue_card_id TO card_news_id;
--   - ALTER TABLE evidence_chain RENAME COLUMN issue_card_id TO card_news_id;
--   - DROP VIEW issue_cards;
--   - Java/Python @Column 매핑 + SQL 컬럼명 정렬

ALTER TABLE issue_cards RENAME TO card_news;

-- 보조 인덱스 명시적 rename (PK 인덱스는 Postgres 가 자동 따라옴, 보조 인덱스 일부는 명시 필요)
DO $$
DECLARE
    idx_name text;
BEGIN
    FOR idx_name IN
        SELECT indexname FROM pg_indexes
        WHERE schemaname = 'public' AND tablename = 'card_news' AND indexname LIKE 'issue_cards_%'
    LOOP
        EXECUTE format('ALTER INDEX %I RENAME TO %I',
                       idx_name,
                       replace(idx_name, 'issue_cards_', 'card_news_'));
    END LOOP;

    FOR idx_name IN
        SELECT indexname FROM pg_indexes
        WHERE schemaname = 'public' AND tablename = 'card_news' AND indexname LIKE 'idx_issue_cards_%'
    LOOP
        EXECUTE format('ALTER INDEX %I RENAME TO %I',
                       idx_name,
                       replace(idx_name, 'idx_issue_cards_', 'idx_card_news_'));
    END LOOP;
END $$;

-- 백워드 호환 VIEW — rollout window 동안 axis-ai 구 image 가 `issue_cards` 로 조회/삽입 가능.
-- simple SELECT * 라 Postgres 가 자동으로 updatable view 로 인식 → INSERT/UPDATE/DELETE 동작.
-- V10 에서 모든 pod 가 새 이름 (card_news) 사용 확인 후 DROP VIEW issue_cards.
CREATE OR REPLACE VIEW issue_cards AS SELECT * FROM card_news;

COMMENT ON VIEW issue_cards IS '(DEPRECATED, V10 drop 대상) V9 의 RENAME 시점 임시 호환 VIEW. axis-ai 가 card_news 로 전환 완료 후 제거.';
