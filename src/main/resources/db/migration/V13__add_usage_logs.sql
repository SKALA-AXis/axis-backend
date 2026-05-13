-- V13: usage_logs — LLM 호출 단위 billing / quota / variance 추적 (slim).
--
-- 담당 middleware: axis-ai/design/90-cross-cutting/token-budget.md (TokenBudgetMiddleware)
-- API: GET /api/admin/usage — 일/주/월 집계 + per-user / per-agent
--      PUT /api/admin/usage/limits — 운영자 budget envelope 조정
--
-- 설계 결정 (slim):
--   · Prompt / response 본문은 본 테이블 에 저장 X — Langfuse self-host 가 SoT.
--   · 본 테이블 = 빌링 집계 + per-user quota + budget enforce 만.
--   · langfuse_trace_id 로 admin UI 에서 Langfuse drill-down.
--   · Sync INSERT (admin_page §3 의 async 권장은 P10+ Redis Stream 도입 시).

CREATE TABLE usage_logs (
    id                       BIGSERIAL PRIMARY KEY,
    occurred_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    occurred_date            DATE NOT NULL,                              -- 일자 집계 인덱스
    user_id                  BIGINT,                                     -- chat / briefing 호출자 (nullable: scheduled)
    actor_kind               VARCHAR(20) NOT NULL,                       -- user / scheduler / admin
    agent                    VARCHAR(120) NOT NULL,                      -- "CardComposerAgent.analyze"
    model                    VARCHAR(60) NOT NULL,                       -- gpt-4o / gpt-4o-mini
    input_tokens             INT NOT NULL,
    output_tokens            INT NOT NULL,
    elapsed_ms               INT,
    cost_krw                 NUMERIC(12,4) NOT NULL,                     -- 자체 추정 (cost_daily_billed 와 비교 대상)
    success                  BOOLEAN NOT NULL,
    error_class              VARCHAR(80),                                -- "RateLimitError" 등. full message 는 Langfuse
    langfuse_trace_id        VARCHAR(80),                                -- Langfuse root trace id
    langfuse_observation_id  VARCHAR(80),                                -- 본 LLM 호출의 span id
    pipeline_log_id          BIGINT,                                     -- pipeline_logs.id (있으면)
    metadata                 JSONB NOT NULL DEFAULT '{}'::jsonb,         -- {prompt_version, request_id, retry_count}
    CONSTRAINT usage_logs_actor_kind_check
        CHECK (actor_kind IN ('user', 'scheduler', 'admin')),
    CONSTRAINT usage_logs_tokens_nonneg
        CHECK (input_tokens >= 0 AND output_tokens >= 0),
    CONSTRAINT usage_logs_cost_nonneg
        CHECK (cost_krw >= 0)
);

CREATE INDEX idx_usage_logs_date
    ON usage_logs(occurred_date, model);

CREATE INDEX idx_usage_logs_agent
    ON usage_logs(agent, occurred_date);

CREATE INDEX idx_usage_logs_user
    ON usage_logs(user_id, occurred_date)
    WHERE user_id IS NOT NULL;

CREATE INDEX idx_usage_logs_trace
    ON usage_logs(langfuse_trace_id)
    WHERE langfuse_trace_id IS NOT NULL;

-- occurred_date 자동 채움 (occurred_at 의 KST date) — admin_page 의 일별 집계 효율화
-- 단순화 위해 application code 가 채우는 방식 채택 (trigger 회피, DST/timezone 명시성)

COMMENT ON TABLE usage_logs IS 'LLM 호출 단위 billing 집계 (slim). Prompt 본문은 Langfuse SoT — 본 테이블은 langfuse_trace_id 로 pointer.';
COMMENT ON COLUMN usage_logs.cost_krw IS '자체 추정 비용. cost_daily_billed (V17) 의 청구액과 variance 비교 대상.';
COMMENT ON COLUMN usage_logs.langfuse_trace_id IS 'Langfuse 의 root trace id — admin UI 의 trace 드릴다운 진입점';
