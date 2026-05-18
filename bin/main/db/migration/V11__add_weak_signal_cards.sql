-- V11: weak_signal_cards — 약한 신호 감지 결과 저장.
--
-- 담당 agent: axis-ai/design/50-weak-signal/weak-signal.md (WeakSignalAgent, 3-phase)
-- Trigger: Spring @Scheduled 월 09:00 KST → POST /weak-signal/run → 감지된 signal 저장
-- 산식: cluster_size_norm × peer_mention_rate (자세히는 design 의 §6)
-- 부착: alert_rules (별도 spec) 와 매칭하여 alerts 테이블 (이미 존재) 에 발송 row 생성

CREATE TABLE weak_signal_cards (
    id              VARCHAR(40) PRIMARY KEY,                -- WS-YYYYMMDD-NNN 형식
    detected_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    signal_type     VARCHAR(40) NOT NULL,                   -- hiring_surge / new_role / dept_concentration /
                                                            -- seniority_shift / tone_anomaly
    peer_id         VARCHAR(40) NOT NULL,                   -- samsung_sds | lg_cns | hyundai_autoever | posco_dx
    strength        VARCHAR(10) NOT NULL,                   -- weak | medium | strong
    confidence      NUMERIC(3,2) NOT NULL,                  -- 0.00 ~ 1.00
    evidence        JSONB NOT NULL DEFAULT '[]'::jsonb,     -- [{raw_article_ids, ratio, weekly_counts, ...}]
    interpretation  TEXT,                                   -- LLM 1줄 해석 (gpt-4o-mini)
    window_days     INT NOT NULL DEFAULT 90,                -- 본 신호의 분석 window
    metadata        JSONB NOT NULL DEFAULT '{}'::jsonb,     -- {detection_version, phase_versions, langfuse_trace_id}
    -- alerts 발송 흔적 (있으면)
    alerts_dispatched INT NOT NULL DEFAULT 0,
    routing_log       JSONB,                                -- [{rule_id, user_id, channel}]
    CONSTRAINT weak_signal_strength_check
        CHECK (strength IN ('weak', 'medium', 'strong')),
    CONSTRAINT weak_signal_confidence_range
        CHECK (confidence >= 0.0 AND confidence <= 1.0),
    CONSTRAINT weak_signal_type_check
        CHECK (signal_type IN ('hiring_surge', 'new_role', 'dept_concentration',
                               'seniority_shift', 'tone_anomaly'))
);

CREATE INDEX idx_weak_signal_peer_time
    ON weak_signal_cards(peer_id, detected_at DESC);

CREATE INDEX idx_weak_signal_type_strength
    ON weak_signal_cards(signal_type, strength, detected_at DESC);

-- frontend `/alerts/dispatched/weak-signals` 쿼리 — 최근 N일치 high strength 만
CREATE INDEX idx_weak_signal_strong_recent
    ON weak_signal_cards(detected_at DESC)
    WHERE strength = 'strong';

COMMENT ON TABLE weak_signal_cards IS 'WeakSignalAgent 의 감지 결과. axis-infra/api/openapi GET /api/weak-signals 응답 원천.';
COMMENT ON COLUMN weak_signal_cards.evidence IS '검출 근거 — raw_article_ids, 산식 값 (ratio / weekly_counts / z-score 등)';
COMMENT ON COLUMN weak_signal_cards.metadata IS 'provenance — detection_version, prompt_version, langfuse_trace_id (해석 LLM call)';
