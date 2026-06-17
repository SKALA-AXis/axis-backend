-- V46: user-authored SK AX strategy context overlays.
--
-- Stores the final user-confirmed text and the LLM-structured overlay JSON.
-- Uploaded file binaries are intentionally not persisted; only file metadata and
-- extracted/edited text are saved.

CREATE TABLE IF NOT EXISTS user_strategy_contexts (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    raw_text TEXT NOT NULL,
    overlay_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    source_type VARCHAR(30) NOT NULL DEFAULT 'manual_text',
    file_name VARCHAR(255),
    file_size BIGINT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_user_strategy_contexts_source_type
        CHECK (source_type IN ('manual_text', 'uploaded_file')),
    CONSTRAINT chk_user_strategy_contexts_raw_text
        CHECK (length(trim(raw_text)) > 0),
    CONSTRAINT chk_user_strategy_contexts_file_size
        CHECK (file_size IS NULL OR file_size >= 0)
);

CREATE INDEX IF NOT EXISTS idx_user_strategy_contexts_user_updated
    ON user_strategy_contexts (user_id, updated_at DESC);

DROP TRIGGER IF EXISTS trg_user_strategy_contexts_touch ON user_strategy_contexts;
CREATE TRIGGER trg_user_strategy_contexts_touch
BEFORE UPDATE ON user_strategy_contexts
FOR EACH ROW
EXECUTE FUNCTION axis_touch_updated_at();

COMMENT ON TABLE user_strategy_contexts IS
    '사용자가 입력·확인한 SK AX 맞춤 전략 자료와 LLM 구조화 overlay JSON 저장소.';
COMMENT ON COLUMN user_strategy_contexts.raw_text IS
    '사용자가 직접 입력하거나 파일에서 추출 후 최종 확인한 자연어 전략 자료.';
COMMENT ON COLUMN user_strategy_contexts.overlay_json IS
    'axis-ai ProfileSnapshotSummarizer가 생성한 skax_user_strategy_context overlay JSON.';
