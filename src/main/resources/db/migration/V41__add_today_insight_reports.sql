-- V41: Home dashboard Today's Insight report memory.
--
-- Stores the UI-ready output payload plus the exact JSON input snapshot used by
-- TodayInsightAgent. This gives the agent durable day-over-day memory without
-- carrying long conversational context.

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE IF NOT EXISTS today_insight_reports (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),

    report_date DATE NOT NULL,
    schema_version VARCHAR(40) NOT NULL DEFAULT 'today_insight_v1',
    prompt_version VARCHAR(80),
    status VARCHAR(30) NOT NULL DEFAULT 'active',

    headline TEXT,
    executive_summary TEXT,
    executive_implication TEXT,

    source_integrated_issue_ids UUID[] NOT NULL DEFAULT '{}',
    source_card_ids TEXT[] NOT NULL DEFAULT '{}',
    source_raw_article_ids BIGINT[] NOT NULL DEFAULT '{}',
    peer_ids TEXT[] NOT NULL DEFAULT '{}',
    sectors TEXT[] NOT NULL DEFAULT '{}',

    input_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    output_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    provenance JSONB NOT NULL DEFAULT '{}'::jsonb,
    confidence DOUBLE PRECISION,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_today_insight_reports_status
        CHECK (status IN ('active', 'archived', 'failed', 'review')),
    CONSTRAINT chk_today_insight_reports_confidence
        CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1))
);

CREATE INDEX IF NOT EXISTS idx_today_insight_reports_latest
    ON today_insight_reports (report_date DESC, created_at DESC)
    WHERE status = 'active';
CREATE INDEX IF NOT EXISTS idx_today_insight_reports_integrated_issues
    ON today_insight_reports USING GIN (source_integrated_issue_ids);
CREATE INDEX IF NOT EXISTS idx_today_insight_reports_cards
    ON today_insight_reports USING GIN (source_card_ids);
CREATE INDEX IF NOT EXISTS idx_today_insight_reports_peers
    ON today_insight_reports USING GIN (peer_ids);
CREATE INDEX IF NOT EXISTS idx_today_insight_reports_sectors
    ON today_insight_reports USING GIN (sectors);
CREATE INDEX IF NOT EXISTS idx_today_insight_reports_output_payload
    ON today_insight_reports USING GIN (output_payload);
CREATE INDEX IF NOT EXISTS idx_today_insight_reports_input_snapshot
    ON today_insight_reports USING GIN (input_snapshot);

CREATE OR REPLACE FUNCTION axis_touch_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_today_insight_reports_updated_at ON today_insight_reports;
CREATE TRIGGER trg_today_insight_reports_updated_at
BEFORE UPDATE ON today_insight_reports
FOR EACH ROW
EXECUTE FUNCTION axis_touch_updated_at();

COMMENT ON TABLE today_insight_reports IS
    'Daily home dashboard insight memory. Stores TodayInsightAgent input_snapshot and output_payload JSONB for durable context.';
COMMENT ON COLUMN today_insight_reports.input_snapshot IS
    'Exact DB/profile/ledger context sent to the agent, trimmed for prompt budget.';
COMMENT ON COLUMN today_insight_reports.output_payload IS
    'UI-ready TodayInsightGenerateResponse payload.';
