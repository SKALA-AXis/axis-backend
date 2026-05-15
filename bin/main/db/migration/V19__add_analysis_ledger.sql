-- V19__add_analysis_ledger.sql
--
-- KnowledgeCuration phase K1 — Analysis Ledger.
-- 분석 agent (Insight / Mixer / Peer / Global / Briefing) 가 결론을 도출하면
-- 즉시 INSERT → 다음 분석 호출 시 ContextPackBuilder 가 carry-over.
-- LLM 미사용 (write-through middleware). 분석 결과의 영속화 + 다음 분석에 보임.
--
-- spec: axis-ai/design/25-knowledge-curation/analysis-ledger.md §9.

CREATE TABLE IF NOT EXISTS analysis_ledger (
    id                   BIGSERIAL    PRIMARY KEY,

    -- 분석 종류 — InsightCascade / MixerAnalysis / PeerComparison / GlobalTrends / Briefing
    analysis_type        VARCHAR(20)  NOT NULL,
    analysis_id          VARCHAR(100) NOT NULL,
    --   ex: IC-20260514-001 (Insight), MX-... (Mixer), BR-... (Briefing)

    -- 분석에 등장한 peer 목록 (1~다 — Mixer 의 다중 peer 가능)
    peer_ids             JSONB        NOT NULL,

    -- 분석의 최종 결론 한 줄 (≤ 100자, SK AX 관점)
    conclusion_one_liner TEXT         NOT NULL,

    -- peer 분석 한정 — 5종 enum (Aggressive Expansion / Defensive Hold /
    --   Tech Pivot / Customer Lock-in / Cost Leadership). Insight/Mixer 는 NULL.
    strategy_label       VARCHAR(30),

    -- 분석 신뢰도 0.0~1.0 — ContextPackBuilder 의 included_in_pack 기준 (>= 0.7)
    confidence           REAL         NOT NULL,

    -- 분석에 사용된 카드 id (환각 검증용 + carry-over 시 출처 trace)
    source_card_ids      JSONB        NOT NULL,

    -- 국내 IT 서비스사 (SK AX) 관점 1~2 문장 (긍정/중립/부정 명시).
    -- Insight/Mixer/Peer/Global 의 sk_ax_implication 필드 carry.
    sk_ax_implication    TEXT,

    -- 3-tier observability Tier 3 — Langfuse Cloud trace 원본 deep link 용.
    -- frontend 사용자엔 노출 X (admin only).
    langfuse_trace_id    VARCHAR(64),

    -- prompt drift tracking — agent 의 prompt_version + git_sha.
    prompt_version       VARCHAR(20),
    git_sha              VARCHAR(12),

    -- ContextPackBuilder 의 fetch_ledger_top_n 가 사용 — confidence < threshold 인
    -- 결과는 INSERT 는 하되 pack 에 carry-over 안 함.
    included_in_pack     BOOLEAN      NOT NULL DEFAULT TRUE,

    -- 후속 분석이 이전 결론을 *반대 방향* 으로 뒤집으면 superseded_by 채움.
    -- 자기 참조 FK (NULL = active, NOT NULL = superseded).
    superseded_by        BIGINT       REFERENCES analysis_ledger(id),

    created_at           TIMESTAMPTZ  DEFAULT now()
);

-- ContextPackBuilder 의 fetch_ledger_top_n hot path — peer_id 별 active 조회.
-- peer_ids 가 JSONB array 라 gin path_ops index 가 효율적.
CREATE INDEX IF NOT EXISTS idx_ledger_peer_active
    ON analysis_ledger USING gin (peer_ids jsonb_path_ops);

-- active (not superseded + included_in_pack) entry 의 최근순 조회.
CREATE INDEX IF NOT EXISTS idx_ledger_active_recent
    ON analysis_ledger (created_at DESC)
    WHERE superseded_by IS NULL AND included_in_pack = TRUE;

-- supersede chain 추적 — 분기 admin review 용 (strategy 가 자꾸 뒤집힘 = 분석 품질 의심)
CREATE INDEX IF NOT EXISTS idx_ledger_supersede
    ON analysis_ledger (superseded_by)
    WHERE superseded_by IS NOT NULL;

COMMENT ON TABLE analysis_ledger IS
    'KnowledgeCuration K1 — 분석 agent 결론의 carry-over 미들웨어 저장소. '
    'with_ledger_writeback decorator 가 분석 종료 직후 INSERT. '
    'ContextPackBuilder 가 다음 분석 호출 시 fetch_ledger_top_n 으로 pack 에 주입.';
