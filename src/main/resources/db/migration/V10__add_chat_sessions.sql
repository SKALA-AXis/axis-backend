-- V10: chat_sessions + chat_turns — FloatingAiChat 의 대화 히스토리 저장.
--
-- 담당 agent: axis-ai/design/40-user-query/chat-orchestrator.md (ChatOrchestratorAgent)
-- API: POST /api/assistant/chat — 사용자 대화 메시지 → axis-ai POST /chat → BE 가 turn 적재
--
-- 설계 결정:
--   · sessions (한 사용자가 시작한 대화 thread) 와 turns (메시지 단위) 를 분리. 향후
--     turn 단위 feedback (V16) 과 join 용이.
--   · session.id 는 UUID — frontend localStorage 가 보유.
--   · turn 의 langfuse_trace_id 로 Langfuse drill-down 가능.

CREATE TABLE chat_sessions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         BIGINT,                                   -- nullable (게스트 / SSO 미연동 시)
    title           VARCHAR(200),                             -- LLM 가 첫 turn 에서 자동 생성
    summary         TEXT,                                     -- 장기 대화 압축 (LLM 가 주기 갱신)
    turn_count      INT NOT NULL DEFAULT 0,                   -- cached count, 매 INSERT 시 trigger 로 +1
    last_message_at TIMESTAMPTZ,
    metadata        JSONB NOT NULL DEFAULT '{}'::jsonb,       -- {client, locale, ...}
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_chat_sessions_user
    ON chat_sessions(user_id, last_message_at DESC)
    WHERE user_id IS NOT NULL;

CREATE INDEX idx_chat_sessions_recent
    ON chat_sessions(last_message_at DESC);

CREATE TABLE chat_turns (
    id                     BIGSERIAL PRIMARY KEY,
    session_id             UUID NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    turn_idx               INT NOT NULL,                      -- 0부터 시작
    role                   VARCHAR(20) NOT NULL,              -- 'user' | 'assistant' | 'system'
    content                TEXT NOT NULL,
    intent                 VARCHAR(40),                       -- search/insight/mixer/peer_compare/smalltalk
    entities               JSONB,                             -- {peer_ids, sectors, date_range, keywords}
    sources                JSONB,                             -- 인용된 card_news id + url
    follow_up_suggestions  JSONB,                             -- UI 버튼용
    confidence             NUMERIC(3,2),                      -- 0.00 ~ 1.00
    langfuse_trace_id      VARCHAR(80),                       -- Langfuse drill-down pointer
    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (session_id, turn_idx),
    CONSTRAINT chat_turns_role_check CHECK (role IN ('user', 'assistant', 'system')),
    CONSTRAINT chat_turns_confidence_range CHECK (confidence IS NULL OR (confidence >= 0.0 AND confidence <= 1.0))
);

CREATE INDEX idx_chat_turns_session
    ON chat_turns(session_id, turn_idx);

CREATE INDEX idx_chat_turns_trace
    ON chat_turns(langfuse_trace_id)
    WHERE langfuse_trace_id IS NOT NULL;

-- turn 적재 시 sessions.turn_count + last_message_at 자동 갱신
CREATE OR REPLACE FUNCTION chat_sessions_touch() RETURNS TRIGGER AS $$
BEGIN
    UPDATE chat_sessions
       SET turn_count = turn_count + 1,
           last_message_at = NEW.created_at,
           updated_at = NOW()
     WHERE id = NEW.session_id;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_chat_turns_touch
AFTER INSERT ON chat_turns
FOR EACH ROW EXECUTE FUNCTION chat_sessions_touch();

COMMENT ON TABLE chat_sessions IS 'FloatingAiChat 대화 thread. ChatOrchestratorAgent (axis-ai) 가 사용.';
COMMENT ON TABLE chat_turns IS '대화의 개별 메시지. role=user/assistant/system. langfuse_trace_id 로 Langfuse drill-down.';
COMMENT ON COLUMN chat_turns.intent IS 'ChatOrchestrator 의 intent 분류 결과 — search/insight/mixer/peer_compare/smalltalk';
