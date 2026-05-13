-- V16: feedback — 사용자 콘텐츠 평가 (👍/👎 / ⭐ rating / 코멘트).
--
-- 담당 spec: axis-infra/docs/admin/feedback.md (FeedbackAgent)
-- API:
--   POST /api/cards/{id}/feedback
--   POST /api/briefings/{id}/feedback
--   POST /api/insights/{id}/feedback
--   POST /api/mixer/{id}/feedback
--   POST /api/chat/turns/{turn_id}/feedback
--
-- 5 artifact_type. BE 가 DB INSERT + Langfuse score API 이중 적재. 사용자 같은
-- artifact 에 30분 내 재제출 시 UPSERT (latest wins) — partial unique index.

CREATE TABLE feedback (
    id                BIGSERIAL PRIMARY KEY,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    user_id           BIGINT NOT NULL,
    artifact_type     VARCHAR(20) NOT NULL,                    -- card / briefing / insight / mixer / chat_turn
    artifact_id       VARCHAR(80) NOT NULL,
    thumbs            VARCHAR(4),                              -- 'up' | 'down' | NULL (rating 만 제출 시)
    rating            INT,                                     -- 1 ~ 5
    comment           TEXT,
    reported_issue    VARCHAR(40),                             -- hallucination / outdated / irrelevant / low_quality / other
    langfuse_trace_id VARCHAR(80),                             -- 평가 대상 trace pointer
    langfuse_score_id VARCHAR(80),                             -- Langfuse score push 응답의 score id (멱등성)
    metadata          JSONB NOT NULL DEFAULT '{}'::jsonb,      -- {prompt_version, llm_model, agent}
    CONSTRAINT feedback_artifact_type_check
        CHECK (artifact_type IN ('card', 'briefing', 'insight', 'mixer', 'chat_turn')),
    CONSTRAINT feedback_thumbs_check
        CHECK (thumbs IS NULL OR thumbs IN ('up', 'down')),
    CONSTRAINT feedback_rating_range
        CHECK (rating IS NULL OR (rating BETWEEN 1 AND 5)),
    CONSTRAINT feedback_issue_check
        CHECK (reported_issue IS NULL OR reported_issue IN
            ('hallucination', 'outdated', 'irrelevant', 'low_quality', 'other')),
    CONSTRAINT feedback_at_least_one_signal
        -- thumbs / rating / reported_issue 중 1개 이상 필수
        CHECK (thumbs IS NOT NULL OR rating IS NOT NULL OR reported_issue IS NOT NULL)
);

-- Note: "같은 user + 같은 artifact 30분 내 재제출" 의 중복 방지는 partial unique index
-- 로는 NOW() 가 immutable 이 아니라 불가. FeedbackService 가 application 측에서 처리:
--   SELECT WHERE user_id=? AND artifact_id=? AND created_at > NOW() - INTERVAL '30 min'
--   → 있으면 UPDATE, 없으면 INSERT.
-- 조회 인덱스 4종
CREATE INDEX idx_feedback_artifact
    ON feedback(artifact_type, artifact_id, created_at DESC);

CREATE INDEX idx_feedback_user
    ON feedback(user_id, created_at DESC);

CREATE INDEX idx_feedback_issue
    ON feedback(reported_issue, created_at DESC)
    WHERE reported_issue IS NOT NULL;

CREATE INDEX idx_feedback_trace
    ON feedback(langfuse_trace_id)
    WHERE langfuse_trace_id IS NOT NULL;

COMMENT ON TABLE feedback IS '사용자 콘텐츠 평가. BE 가 INSERT + Langfuse score 이중 적재. 30분 내 재제출 시 UPSERT.';
COMMENT ON COLUMN feedback.langfuse_trace_id IS '평가 대상 trace (card 의 evidence_chain.provenance.langfuse_trace_id 또는 chat_turns.langfuse_trace_id 와 일치)';
COMMENT ON COLUMN feedback.reported_issue IS 'hallucination 신고 시 BE 가 즉시 ops 이메일 alert';
