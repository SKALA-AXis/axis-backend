-- V15: user_events — frontend 행동 트래커 적재.
--
-- 담당 spec: axis-infra/docs/admin/user_events.md (UserEventTracker)
-- API: POST /api/events (batch, 5개씩 또는 10초마다 flush)
--
-- 10 event_type 카탈로그:
--   page_view / search / card_click / card_share / bookmark_add / bookmark_remove /
--   feedback_submit / regenerate / chat_message / login / logout / login_fail / export
--
-- DAU / WAU / MAU + 기능 사용률 + zero-result 비율 + 사용자별 호출 Top N 측정의 원천.

CREATE TABLE user_events (
    id           BIGSERIAL PRIMARY KEY,
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    user_id      BIGINT,                                       -- nullable: 게스트 (login 이벤트 등)
    session_id   UUID,                                         -- frontend localStorage UUID
    event_type   VARCHAR(40) NOT NULL,
    event_props  JSONB NOT NULL DEFAULT '{}'::jsonb,           -- {route, query, card_id, peer_id, position, ...}
    ip           INET,
    user_agent   TEXT,
    request_id   UUID                                          -- BE MDC trace id — usage_logs / audit_logs 와 join
);

CREATE INDEX idx_user_events_type_time
    ON user_events(event_type, occurred_at DESC);

CREATE INDEX idx_user_events_user_time
    ON user_events(user_id, occurred_at DESC)
    WHERE user_id IS NOT NULL;

CREATE INDEX idx_user_events_session_time
    ON user_events(session_id, occurred_at)
    WHERE session_id IS NOT NULL;

CREATE INDEX idx_user_events_props
    ON user_events USING GIN (event_props);

COMMENT ON TABLE user_events IS 'FE tracker SDK (UserEventTracker) 가 적재. DAU/WAU/MAU + zero-result 검색 + Top N 사용자 분석 원천.';
COMMENT ON COLUMN user_events.event_type IS '10 카탈로그: page_view/search/card_click/card_share/bookmark_add/bookmark_remove/feedback_submit/regenerate/chat_message/login/logout/login_fail/export';
COMMENT ON COLUMN user_events.event_props IS 'jsonb — event_type 별로 다른 schema. 자유 형식. GIN index 로 검색 가능';
COMMENT ON COLUMN user_events.request_id IS 'BE MDC trace id. usage_logs / audit_logs 의 동일 컬럼과 grep/join 가능';
