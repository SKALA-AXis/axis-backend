-- V12: briefing_reports — user-triggered async briefing 생성 결과.
--
-- 담당 agent: axis-ai/design/60-briefing/briefing-generation.md (BriefingGenerationAgent)
-- API: POST /api/briefings/generate → axis-ai POST /briefings/generate (202 Accepted, background)
--      → polling GET /api/briefings/{id}/status / GET /api/briefings/{id}
--
-- vs /pipeline/delivery: daily SES 이메일은 별개 (BE BriefingService.generateAndSend).
-- 본 테이블은 *user 요청 화면용 문서* 보존.

CREATE TABLE briefing_reports (
    id                    VARCHAR(40) PRIMARY KEY,           -- BR-YYYYMMDD-NNN
    title                 VARCHAR(500) NOT NULL,
    briefing_type         VARCHAR(20) NOT NULL,              -- daily | weekly | custom
    date_from             DATE NOT NULL,
    date_to               DATE NOT NULL,
    requested_by_user_id  BIGINT,
    status                VARCHAR(20) NOT NULL DEFAULT 'queued',  -- queued | running | completed | completed_partial | failed
    progress              NUMERIC(3,2) NOT NULL DEFAULT 0.0,      -- 0.0 ~ 1.0 (polling)
    payload               JSONB,                              -- BriefingReport 전체 (executive_summary, immediate_trends, watch_trends, sections, sources)
    error_message         TEXT,                               -- status=failed / completed_partial 시
    confidence            NUMERIC(3,2),
    provenance            JSONB,                              -- {llm_model, prompt_versions, run_at, git_sha, langfuse_trace_id, source_card_ids}
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at          TIMESTAMPTZ,
    CONSTRAINT briefing_reports_status_check
        CHECK (status IN ('queued', 'running', 'completed', 'completed_partial', 'failed')),
    CONSTRAINT briefing_reports_type_check
        CHECK (briefing_type IN ('daily', 'weekly', 'custom')),
    CONSTRAINT briefing_reports_progress_range
        CHECK (progress >= 0.0 AND progress <= 1.0),
    CONSTRAINT briefing_reports_confidence_range
        CHECK (confidence IS NULL OR (confidence >= 0.0 AND confidence <= 1.0)),
    CONSTRAINT briefing_reports_date_order
        CHECK (date_from <= date_to)
);

CREATE INDEX idx_briefing_status
    ON briefing_reports(status, created_at DESC);

CREATE INDEX idx_briefing_user
    ON briefing_reports(requested_by_user_id, created_at DESC)
    WHERE requested_by_user_id IS NOT NULL;

CREATE INDEX idx_briefing_date_range
    ON briefing_reports(date_from, date_to);

-- cleanup cron 용: 30분 이상 'running' 상태인 row → 'failed' 전환 (pod crash 복구)
-- 본 마이그는 cron 자체는 만들지 않음. 별도 Spring @Scheduled 또는 axis-ai job 으로 처리.

COMMENT ON TABLE briefing_reports IS 'user-triggered BriefingReport 생성 결과 (60-briefing/briefing-generation.md). daily SES 메일은 별개 (briefing_history).';
COMMENT ON COLUMN briefing_reports.payload IS 'BriefingReport jsonb — executive_summary (3~5 문단), immediate_trends[], watch_trends[], sections[], sources[]';
COMMENT ON COLUMN briefing_reports.provenance IS 'LLM 호출 추적 — Langfuse drill-down 가능';
