-- ============================================================
-- V3 — 메일 전달 + MBB 보고서 baseline 테이블 추가
--
-- 배경: DB_METADATA.md §5 의 미정의 테이블 3종을 양성화.
--   1) recipients      — CardSelectorAgent 가 수신자 role 별 차등 필터링
--   2) briefing_history — EmailAgent 의 메일 발송 이력 (감사 + 재시도 추적)
--   3) mbb_baseline    — McKinsey / Bain / BCG 등 컨설팅 보고서 baseline
--
-- 작성 주체:
--   · INSERT: axis-ai (CardSelectorAgent · EmailAgent — 별도 PR 예정)
--   · SELECT: axis-backend (감사·통계 · 운영자 도구)
-- ============================================================


-- ─── 11. recipients — 메일 수신자 ──────────────────────────
-- CardSelectorAgent 가 role 별 impact_threshold 로 차등 필터링.
-- PM = impact ≥ 3 전체 카드 / 임원 = impact ≥ 4 핵심 카드.
CREATE TABLE IF NOT EXISTS recipients (
    id                  BIGSERIAL    PRIMARY KEY,

    email               VARCHAR(255) NOT NULL UNIQUE,
    name                VARCHAR(100),
    role                VARCHAR(20)  NOT NULL,           -- pm | executive | admin
    impact_threshold    SMALLINT     NOT NULL DEFAULT 3, -- 1~5 (포함될 카드의 최소 importance_score · PM=3, 임원=4)
    locale              VARCHAR(10)  DEFAULT 'ko-KR',

    is_active           BOOLEAN      DEFAULT TRUE,
    created_at          TIMESTAMPTZ  DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_recipients_active
    ON recipients (is_active, role);


-- ─── 12. briefing_history — 메일 발송 이력 ──────────────────
-- 평일 08:30 EmailAgent 가 발송 후 INSERT.
-- status=skipped (card_count==0) / sent / failed 모두 기록.
-- DLQ 큐 + 운영자 alert 의 단일 소스.
CREATE TABLE IF NOT EXISTS briefing_history (
    id                  BIGSERIAL    PRIMARY KEY,

    -- 어느 수신자에게 어떤 카드가 어느 날짜로
    recipient_id        BIGINT       NOT NULL REFERENCES recipients(id) ON DELETE RESTRICT,
    briefing_date       DATE         NOT NULL,                    -- 어느 영업일의 브리핑인지
    run_id              VARCHAR(50),                              -- 한 cycle 의 식별자 (재시도 추적)

    -- 콘텐츠
    card_count          INT          NOT NULL DEFAULT 0,
    card_ids            TEXT[]       DEFAULT '{}',                -- 포함된 issue_cards.id 배열
    subject             TEXT,                                      -- 메일 제목 (저장은 옵션)
    body_preview        TEXT,                                      -- 본문 첫 200자 (감사 + 디버깅)

    -- 발송 결과
    status              VARCHAR(20)  NOT NULL,                    -- sent | failed | skipped
    smtp_response       TEXT,                                      -- SendGrid/SMTP 응답 코드 + 메시지
    error_msg           TEXT,                                      -- status=failed 시 상세
    retry_count         SMALLINT     DEFAULT 0,                   -- EmailAgent 의 retry ×3 횟수

    sent_at             TIMESTAMPTZ  DEFAULT NOW(),
    created_at          TIMESTAMPTZ  DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_briefing_history_recipient_date
    ON briefing_history (recipient_id, briefing_date DESC);
CREATE INDEX IF NOT EXISTS idx_briefing_history_run
    ON briefing_history (run_id);
CREATE INDEX IF NOT EXISTS idx_briefing_history_status
    ON briefing_history (status);


-- ─── 13. mbb_baseline — 컨설팅 보고서 baseline ──────────────
-- McKinsey · Bain · BCG · 커니 등 글로벌 컨설팅사 보고서를 baseline 으로 보관.
-- EvidenceAgent 가 issue_cards.implication 의 키워드와 매칭하여
-- evidence_chain.mbb_refs 에 첨부 (W5 활성).
CREATE TABLE IF NOT EXISTS mbb_baseline (
    id                  BIGSERIAL    PRIMARY KEY,

    source              VARCHAR(50)  NOT NULL,                    -- 'McKinsey' | 'BCG' | 'Bain' | 'Kearney' | ...
    report_id           VARCHAR(100),                              -- 발행처 내부 식별자 (있으면)
    title               TEXT         NOT NULL,
    published_date      DATE,
    url                 TEXT,                                      -- 원문 URL (PDF · 웹 페이지)
    summary             TEXT,                                      -- 보고서 핵심 요약 (운영자 입력 또는 AI 생성)

    -- 매칭 키 — EvidenceAgent 가 카드 sector/event/keyword 와 매칭
    sectors             TEXT[]       DEFAULT '{}',                -- ['ai_tech', 'security'] 등
    keywords            TEXT[]       DEFAULT '{}',                -- 매칭 키워드 배열

    raw_payload         JSONB        DEFAULT '{}',                -- 추가 메타 (저자 · 페이지 수 등)

    is_active           BOOLEAN      DEFAULT TRUE,
    created_at          TIMESTAMPTZ  DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_mbb_baseline_source_date
    ON mbb_baseline (source, published_date DESC);
CREATE INDEX IF NOT EXISTS idx_mbb_baseline_active
    ON mbb_baseline (is_active);
