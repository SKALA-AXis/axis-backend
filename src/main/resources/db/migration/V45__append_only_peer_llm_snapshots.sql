-- V45: Peer+ LLM snapshot append-only 버저닝 + 생성 run 이력.
--
-- 배경: 이 DDL 은 2026-06-11 저녁 미커밋 초안(V44)이 로컬 실행으로 운영 DB 에
-- 먼저 적용됐던 내용을 DB 실상태에서 역산해 정식 수록한 것이다 (유령 V44 히스토리
-- 행은 제거됨). 운영 DB 에는 모든 객체가 이미 존재하므로 전 구문을 멱등 가드로
-- 작성했다 — 운영에선 no-op, 빈 환경에선 정상 적용.
--
-- 내용:
-- 1) peer_llm_analysis_runs — LLM 생성 시도(run) 단위 이력 테이블
-- 2) peer_llm_analysis_snapshots 에 run_id/promoted_at/review_note 추가
-- 3) status 라이프사이클 확장: candidate → review → active → archived
--    (+ failed/rejected). active 는 (analysis_type, peer_id, comparison_mode)
--    당 1건만 허용하는 부분 unique 로 강제 — 갱신은 UPDATE 가 아니라
--    새 row append 후 승격(promoted_at) 방식.
-- 4) evidence unique 인덱스를 비-unique 조회 인덱스로 교체 — 동일 evidence 라도
--    재생성 시도를 새 row 로 보존(append-only).

CREATE TABLE IF NOT EXISTS peer_llm_analysis_runs (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    analysis_type VARCHAR(80) NOT NULL DEFAULT 'peer_swot_comparison',
    prompt_version VARCHAR(80) NOT NULL,
    model_name VARCHAR(80),
    generation_params JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(30) NOT NULL DEFAULT 'running',
    memo TEXT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_peer_llm_analysis_runs_params_object
        CHECK (jsonb_typeof(generation_params) = 'object'),
    CONSTRAINT chk_peer_llm_analysis_runs_status
        CHECK (status IN ('running', 'completed', 'failed'))
);

CREATE INDEX IF NOT EXISTS idx_peer_llm_analysis_runs_latest
    ON peer_llm_analysis_runs (
        analysis_type,
        prompt_version,
        started_at DESC,
        created_at DESC
    );

DROP TRIGGER IF EXISTS trg_peer_llm_analysis_runs_updated_at
    ON peer_llm_analysis_runs;
CREATE TRIGGER trg_peer_llm_analysis_runs_updated_at
BEFORE UPDATE ON peer_llm_analysis_runs
FOR EACH ROW
EXECUTE FUNCTION axis_touch_updated_at();

ALTER TABLE peer_llm_analysis_snapshots
    ADD COLUMN IF NOT EXISTS run_id UUID,
    ADD COLUMN IF NOT EXISTS promoted_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS review_note TEXT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'peer_llm_analysis_snapshots_run_id_fkey'
    ) THEN
        ALTER TABLE peer_llm_analysis_snapshots
            ADD CONSTRAINT peer_llm_analysis_snapshots_run_id_fkey
                FOREIGN KEY (run_id) REFERENCES peer_llm_analysis_runs(id);
    END IF;
END
$$;

ALTER TABLE peer_llm_analysis_snapshots
    DROP CONSTRAINT IF EXISTS chk_peer_llm_analysis_snapshots_status;
ALTER TABLE peer_llm_analysis_snapshots
    ADD CONSTRAINT chk_peer_llm_analysis_snapshots_status
        CHECK (status IN ('candidate', 'review', 'active', 'archived', 'failed', 'rejected'));

-- append-only 전환: 동일 evidence 재생성도 새 row 로 보존하고,
-- 노출 단일성은 active 부분 unique 가 담당한다.
DROP INDEX IF EXISTS uq_peer_llm_analysis_snapshots_evidence;

CREATE INDEX IF NOT EXISTS idx_peer_llm_analysis_snapshots_evidence_lookup
    ON peer_llm_analysis_snapshots (
        analysis_type,
        peer_id,
        comparison_mode,
        evidence_hash,
        prompt_version,
        COALESCE(model_name, '')
    );

CREATE UNIQUE INDEX IF NOT EXISTS uq_peer_llm_analysis_snapshots_active
    ON peer_llm_analysis_snapshots (analysis_type, peer_id, comparison_mode)
    WHERE status = 'active';

CREATE INDEX IF NOT EXISTS idx_peer_llm_analysis_snapshots_run_id
    ON peer_llm_analysis_snapshots (run_id);

COMMENT ON TABLE peer_llm_analysis_runs IS
    'Peer+ LLM 분석 생성 시도(run) 이력. snapshot 들이 run_id 로 묶인다.';
COMMENT ON COLUMN peer_llm_analysis_snapshots.run_id IS
    '이 snapshot 을 생성한 run. NULL 은 run 추적 도입 이전 row.';
COMMENT ON COLUMN peer_llm_analysis_snapshots.promoted_at IS
    'candidate/review → active 승격 시각. append-only 정책에서 노출 전환 추적용.';
COMMENT ON COLUMN peer_llm_analysis_snapshots.review_note IS
    '리뷰/반려 사유 메모.';
