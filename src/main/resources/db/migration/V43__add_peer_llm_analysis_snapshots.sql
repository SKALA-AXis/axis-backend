-- V43: Peer+ LLM comparison analysis snapshots.
--
-- Stores generated Peer+ comparison/SWOT JSON separately from source facts.
-- The source fact tables remain authoritative; this table keeps UI-ready LLM
-- output plus the exact compact evidence snapshot used to produce it.

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE IF NOT EXISTS peer_llm_analysis_snapshots (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),

    analysis_type VARCHAR(80) NOT NULL DEFAULT 'peer_swot_comparison',
    scope VARCHAR(30) NOT NULL,
    peer_id TEXT NOT NULL,
    reference_peer_id TEXT NOT NULL DEFAULT 'sk_ax',
    comparison_mode VARCHAR(80) NOT NULL,
    schema_version VARCHAR(40) NOT NULL DEFAULT 'peer_swot_comparison_v1',
    prompt_version VARCHAR(80) NOT NULL,
    model_name VARCHAR(80),
    status VARCHAR(30) NOT NULL DEFAULT 'active',

    evidence_hash VARCHAR(80) NOT NULL,
    input_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    output_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    analysis_trace JSONB NOT NULL DEFAULT '[]'::jsonb,
    provenance JSONB NOT NULL DEFAULT '{}'::jsonb,
    confidence DOUBLE PRECISION,

    source_raw_article_ids BIGINT[] NOT NULL DEFAULT '{}',
    source_signal_ids BIGINT[] NOT NULL DEFAULT '{}',
    source_metric_ids BIGINT[] NOT NULL DEFAULT '{}',
    peer_ids TEXT[] NOT NULL DEFAULT '{}',

    generated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_peer_llm_analysis_snapshots_scope
        CHECK (scope IN ('all', 'company')),
    CONSTRAINT chk_peer_llm_analysis_snapshots_status
        CHECK (status IN ('active', 'archived', 'failed', 'review')),
    CONSTRAINT chk_peer_llm_analysis_snapshots_confidence
        CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
    CONSTRAINT chk_peer_llm_analysis_snapshots_payload_object
        CHECK (jsonb_typeof(output_payload) = 'object'),
    CONSTRAINT chk_peer_llm_analysis_snapshots_trace_array
        CHECK (jsonb_typeof(analysis_trace) = 'array')
);

CREATE INDEX IF NOT EXISTS idx_peer_llm_analysis_snapshots_latest
    ON peer_llm_analysis_snapshots (
        analysis_type,
        scope,
        peer_id,
        comparison_mode,
        generated_at DESC,
        created_at DESC
    )
    WHERE status = 'active';

CREATE INDEX IF NOT EXISTS idx_peer_llm_analysis_snapshots_expires_at
    ON peer_llm_analysis_snapshots (expires_at)
    WHERE status = 'active' AND expires_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_peer_llm_analysis_snapshots_peer_ids
    ON peer_llm_analysis_snapshots USING GIN (peer_ids);

CREATE INDEX IF NOT EXISTS idx_peer_llm_analysis_snapshots_raw_articles
    ON peer_llm_analysis_snapshots USING GIN (source_raw_article_ids);

CREATE INDEX IF NOT EXISTS idx_peer_llm_analysis_snapshots_signals
    ON peer_llm_analysis_snapshots USING GIN (source_signal_ids);

CREATE INDEX IF NOT EXISTS idx_peer_llm_analysis_snapshots_metrics
    ON peer_llm_analysis_snapshots USING GIN (source_metric_ids);

CREATE INDEX IF NOT EXISTS idx_peer_llm_analysis_snapshots_output_payload
    ON peer_llm_analysis_snapshots USING GIN (output_payload);

CREATE INDEX IF NOT EXISTS idx_peer_llm_analysis_snapshots_input_snapshot
    ON peer_llm_analysis_snapshots USING GIN (input_snapshot);

CREATE INDEX IF NOT EXISTS idx_peer_llm_analysis_snapshots_analysis_trace
    ON peer_llm_analysis_snapshots USING GIN (analysis_trace);

CREATE UNIQUE INDEX IF NOT EXISTS uq_peer_llm_analysis_snapshots_evidence
    ON peer_llm_analysis_snapshots (
        analysis_type,
        peer_id,
        comparison_mode,
        evidence_hash,
        prompt_version,
        COALESCE(model_name, '')
    );

CREATE OR REPLACE FUNCTION axis_touch_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_peer_llm_analysis_snapshots_updated_at
    ON peer_llm_analysis_snapshots;
CREATE TRIGGER trg_peer_llm_analysis_snapshots_updated_at
BEFORE UPDATE ON peer_llm_analysis_snapshots
FOR EACH ROW
EXECUTE FUNCTION axis_touch_updated_at();

COMMENT ON TABLE peer_llm_analysis_snapshots IS
    'Peer+ LLM comparison/SWOT analysis snapshots. Stores compact input evidence and UI-ready output JSON.';
COMMENT ON COLUMN peer_llm_analysis_snapshots.scope IS
    'all for the full competitor comparison, company for a single peer vs SK AX comparison.';
COMMENT ON COLUMN peer_llm_analysis_snapshots.peer_id IS
    'all, samsung_sds, lg_cns, hyundai_autoever, posco_dx, etc.';
COMMENT ON COLUMN peer_llm_analysis_snapshots.input_snapshot IS
    'Compact evidence pack sent to the LLM, including SK AX reference evidence and competitor evidence.';
COMMENT ON COLUMN peer_llm_analysis_snapshots.output_payload IS
    'Validated UI-ready JSON from the Peer+ LLM analysis.';
COMMENT ON COLUMN peer_llm_analysis_snapshots.analysis_trace IS
    'User-reviewable reasoning summary extracted from output_payload.analysis_trace.';
COMMENT ON COLUMN peer_llm_analysis_snapshots.evidence_hash IS
    'Hash of the compact input evidence pack. Used to avoid unnecessary LLM regeneration.';
