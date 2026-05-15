-- V17: cost_daily_billed — OpenAI / Anthropic 등 벤더의 *실 청구* 비용 동기화.
--
-- 담당 spec: axis-infra/docs/admin/cost_reconciliation.md (CostReconciliationJob)
-- Trigger: Spring @Scheduled 매일 03:00 KST → OpenAI Usage API → UPSERT
-- 활용: usage_logs (V13, 자체 추정) 와 variance 비교 → > 10% 시 ops 이메일 alert
--
-- 환율 스냅샷 (krw_per_usd) 컬럼으로 재현성 보장. raw_payload jsonb 로 audit 가능.

CREATE TABLE cost_daily_billed (
    id                   BIGSERIAL PRIMARY KEY,
    date                 DATE NOT NULL,
    vendor               VARCHAR(40) NOT NULL,                       -- openai / anthropic / ...
    model                VARCHAR(60) NOT NULL,
    billed_cost_usd      NUMERIC(12,6) NOT NULL,
    billed_input_tokens  BIGINT,
    billed_output_tokens BIGINT,
    krw_per_usd          NUMERIC(8,2) NOT NULL,                      -- 환율 스냅샷 (변경 시 PR)
    billed_cost_krw      NUMERIC(14,4) GENERATED ALWAYS AS (billed_cost_usd * krw_per_usd) STORED,
    fetched_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    source_uri           VARCHAR(200),                               -- "openai:/v1/usage?date=2026-05-12"
    raw_payload          JSONB,                                      -- 원본 API 응답 (audit)
    CONSTRAINT cost_billed_unique UNIQUE (date, vendor, model),
    CONSTRAINT cost_billed_nonneg
        CHECK (billed_cost_usd >= 0 AND krw_per_usd > 0)
);

CREATE INDEX idx_cost_billed_date
    ON cost_daily_billed(date DESC);

CREATE INDEX idx_cost_billed_vendor_date
    ON cost_daily_billed(vendor, date DESC);

COMMENT ON TABLE cost_daily_billed IS '벤더 실 청구 비용 (OpenAI Usage API 등). 자체 추정 usage_logs (V13) 와 variance 비교.';
COMMENT ON COLUMN cost_daily_billed.krw_per_usd IS '환율 스냅샷. 청구액 재현 시 사용. 변경은 PR (config 상수)';
COMMENT ON COLUMN cost_daily_billed.billed_cost_krw IS 'GENERATED — billed_cost_usd × krw_per_usd. SELECT 효율화';
