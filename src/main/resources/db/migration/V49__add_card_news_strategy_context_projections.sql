-- User-specific card news strategy-context projections.
--
-- Keep card_news as the shared/base card.  When a user applies custom SK AX
-- strategy context to a card, store only that user's regenerated action copy
-- here. Summary and insight stay on the base card.

CREATE TABLE IF NOT EXISTS card_news_strategy_context_projections (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    card_news_id VARCHAR(50) NOT NULL REFERENCES card_news(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    applied_action JSONB NOT NULL DEFAULT '{}'::jsonb,
    is_applied BOOLEAN NOT NULL DEFAULT TRUE,
    applied_at TIMESTAMPTZ,
    reverted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_card_news_strategy_projection_user_card
        UNIQUE (card_news_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_card_news_strategy_projections_user_card
    ON card_news_strategy_context_projections (user_id, card_news_id);

CREATE INDEX IF NOT EXISTS idx_card_news_strategy_projections_card
    ON card_news_strategy_context_projections (card_news_id);

DROP TRIGGER IF EXISTS trg_card_news_strategy_context_projections_touch
    ON card_news_strategy_context_projections;
CREATE TRIGGER trg_card_news_strategy_context_projections_touch
BEFORE UPDATE ON card_news_strategy_context_projections
FOR EACH ROW
EXECUTE FUNCTION axis_touch_updated_at();

COMMENT ON TABLE card_news_strategy_context_projections IS
    '사용자별 맞춤 전략 적용 카드뉴스 projection. card_news 원본은 변경하지 않는다.';
COMMENT ON COLUMN card_news_strategy_context_projections.applied_action IS
    '사용자 맞춤 전략자료를 반영해 재생성한 카드뉴스 대응방안 전용 JSON.';
