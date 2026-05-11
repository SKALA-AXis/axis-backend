-- ============================================================
-- V6 — peer_companies.role 컬럼 제거 (V5 의 over-engineering 정정)
--
-- 배경: V5 에서 peer/self 분기를 위해 role 컬럼을 추가했으나,
--       자사가 영원히 단일 row (sk_ax) 인 도메인에서는
--       id 자체로 충분히 식별 가능 — role 컬럼은 사실상
--       single-value 필드라 정보 가치가 없다고 판단.
--
-- 결정: id='sk_ax' 가 곧 자사. role 컬럼 폐기.
--
-- sk_ax 시드 row 는 그대로 보존 (V5 INSERT 의 데이터 가치는 유지).
--
-- 동반 axis-ai 변경 (별도 PR — V5 의 role 사용 코드를 작성하지 않은 상태):
--   · IssueCardAgent      — peer.id == 'sk_ax' 면 카드 생성 skip
--                           (또는 SELF_PEER_IDS = {'sk_ax'} config 상수)
--   · ExposureScoreAgent  — peer.id == 'sk_ax' 인 경우 peer_mention_rate=0 강제
--   · 크롤러 config       — peer_id='sk_ax' 키워드는 그대로 추가
-- ============================================================

DROP INDEX IF EXISTS idx_peer_companies_role;
ALTER TABLE peer_companies DROP COLUMN IF EXISTS role;
