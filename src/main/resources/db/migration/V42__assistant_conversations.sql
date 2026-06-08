-- V42: lightweight assistant conversation memory.
--
-- Keep the initial schema compact: one conversation table and one message table.
-- Sources, retrieval traces, and page handoffs stay in JSONB on assistant_messages
-- until query volume proves they need dedicated relation tables.

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE IF NOT EXISTS assistant_conversations (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    device_id_hash VARCHAR(128),

    title VARCHAR(200),
    summary TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'active',

    last_message_at TIMESTAMPTZ,
    message_count INT NOT NULL DEFAULT 0,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_assistant_conversations_owner
        CHECK (user_id IS NOT NULL OR device_id_hash IS NOT NULL),
    CONSTRAINT chk_assistant_conversations_status
        CHECK (status IN ('active', 'ended', 'archived', 'deleted')),
    CONSTRAINT chk_assistant_conversations_message_count
        CHECK (message_count >= 0)
);

CREATE INDEX IF NOT EXISTS idx_assistant_conversations_user_latest
    ON assistant_conversations (user_id, last_message_at DESC NULLS LAST, created_at DESC)
    WHERE user_id IS NOT NULL AND status <> 'deleted';

CREATE INDEX IF NOT EXISTS idx_assistant_conversations_device_latest
    ON assistant_conversations (device_id_hash, last_message_at DESC NULLS LAST, created_at DESC)
    WHERE device_id_hash IS NOT NULL AND status <> 'deleted';

CREATE TABLE IF NOT EXISTS assistant_messages (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    conversation_id UUID NOT NULL REFERENCES assistant_conversations(id) ON DELETE CASCADE,

    turn_idx INT NOT NULL,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,

    intent VARCHAR(60),
    scope VARCHAR(60),
    entities JSONB NOT NULL DEFAULT '{}'::jsonb,

    answer_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    sources JSONB NOT NULL DEFAULT '[]'::jsonb,
    retrieval_trace JSONB NOT NULL DEFAULT '{}'::jsonb,
    handoff JSONB NOT NULL DEFAULT '{}'::jsonb,
    safety JSONB NOT NULL DEFAULT '{}'::jsonb,

    confidence DOUBLE PRECISION,
    langfuse_trace_id VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_assistant_messages_turn UNIQUE (conversation_id, turn_idx),
    CONSTRAINT chk_assistant_messages_role
        CHECK (role IN ('user', 'assistant', 'system')),
    CONSTRAINT chk_assistant_messages_confidence
        CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1))
);

CREATE INDEX IF NOT EXISTS idx_assistant_messages_conversation
    ON assistant_messages (conversation_id, turn_idx);

CREATE INDEX IF NOT EXISTS idx_assistant_messages_created_at
    ON assistant_messages (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_assistant_messages_intent
    ON assistant_messages (intent, created_at DESC)
    WHERE intent IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_assistant_messages_sources_gin
    ON assistant_messages USING GIN (sources);

CREATE INDEX IF NOT EXISTS idx_assistant_messages_handoff_gin
    ON assistant_messages USING GIN (handoff);

CREATE INDEX IF NOT EXISTS idx_assistant_messages_trace
    ON assistant_messages (langfuse_trace_id)
    WHERE langfuse_trace_id IS NOT NULL;

CREATE OR REPLACE FUNCTION assistant_conversations_touch() RETURNS TRIGGER AS $$
BEGIN
    UPDATE assistant_conversations
       SET message_count = message_count + 1,
           last_message_at = NEW.created_at,
           updated_at = NOW()
     WHERE id = NEW.conversation_id;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_assistant_messages_touch ON assistant_messages;
CREATE TRIGGER trg_assistant_messages_touch
AFTER INSERT ON assistant_messages
FOR EACH ROW EXECUTE FUNCTION assistant_conversations_touch();

DROP TRIGGER IF EXISTS trg_assistant_conversations_updated_at ON assistant_conversations;
CREATE TRIGGER trg_assistant_conversations_updated_at
BEFORE UPDATE ON assistant_conversations
FOR EACH ROW EXECUTE FUNCTION axis_touch_updated_at();

COMMENT ON TABLE assistant_conversations IS
    'Floating assistant conversation thread. Owned by users.id when logged in, otherwise by hashed HttpOnly device id.';
COMMENT ON TABLE assistant_messages IS
    'Assistant/user turns. Sources, retrieval trace, safety decisions, and page handoff payloads are kept in compact JSONB.';
COMMENT ON COLUMN assistant_messages.sources IS
    'Grounding sources used in the assistant answer: card_news, integrated_issues, today_insight_reports, briefing_reports, mixer_results, or raw_articles references.';
COMMENT ON COLUMN assistant_messages.handoff IS
    'Navigation handoff payload. The assistant prepares target route and candidate ids; execution happens on the target page.';
