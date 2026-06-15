-- V46: 대형 이벤트(수주·파트너십·M&A) 1회성 이메일 알림 발송 추적.
--
-- 배경: exposure/importance 점수 기반 알림은 정밀도가 낮아 객관적이지 않다. 그래서
-- event_type ∈ {ma, contract, partnership} 인 "누가 봐도 큰 이벤트"에 한해 고정밀
-- 규칙 게이트로만 이메일을 발송한다. 이 테이블은 "이미 보낸 알림"을 추적해
-- 같은/비슷한 뉴스(동일 cluster_id 로 묶인 사건)에 중복 발송되는 것을 막는다
-- (dedupe_key UNIQUE = "1회만" 보장). 발송 주체는 backend EventAlertService
-- → SesMailService (AWS SES V2 + IRSA). axis-ai 는 무관(card_news 만 읽음).
--
-- 멱등: 빈 환경 정상 생성, 재실행 no-op (IF NOT EXISTS). id 는 app 이 채운다
-- (uuid_generate_v4 의존 제거).

CREATE TABLE IF NOT EXISTS sent_alerts (
    id               UUID PRIMARY KEY,
    dedupe_key       VARCHAR(200) NOT NULL,
    card_news_id     VARCHAR(50),
    cluster_id       BIGINT,
    peer_id          VARCHAR(50),
    event_type       VARCHAR(50),
    title            TEXT,
    importance_score REAL,
    recipients       TEXT NOT NULL,
    subject          TEXT,
    trigger_source   VARCHAR(30) NOT NULL DEFAULT 'auto',
    ses_message_id   VARCHAR(200),
    status           VARCHAR(20) NOT NULL DEFAULT 'sent',
    sent_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uk_sent_alerts_dedupe UNIQUE (dedupe_key),
    CONSTRAINT chk_sent_alerts_trigger_source
        CHECK (trigger_source IN ('auto', 'demo', 'manual')),
    CONSTRAINT chk_sent_alerts_status
        CHECK (status IN ('sent', 'failed', 'skipped'))
);

-- 30일 내 같은 peer+event_type 재발송 억제(보조 dedup) 조회용.
CREATE INDEX IF NOT EXISTS idx_sent_alerts_peer_event_sent
    ON sent_alerts (peer_id, event_type, sent_at DESC);

-- 최근 발송 이력 조회용.
CREATE INDEX IF NOT EXISTS idx_sent_alerts_sent_at
    ON sent_alerts (sent_at DESC);

COMMENT ON TABLE sent_alerts IS
    '대형 이벤트(수주/파트너십/M&A) 1회성 이메일 알림 발송 이력 + 중복 방지(dedupe_key UNIQUE)';
