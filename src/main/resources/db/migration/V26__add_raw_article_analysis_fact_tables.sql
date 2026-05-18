-- V26: IR/DART parser 결과에서 파생되는 분석 fact 테이블 추가.
--
-- source-specific metadata(raw_article_metadata_ir/dart)는 원문/수집 메타와
-- parser 결과 보존에 집중하고, 숫자 fact와 사업 신호는 공통 분석 테이블로 분리한다.

CREATE TABLE IF NOT EXISTS raw_article_financial_metrics (
    id BIGSERIAL PRIMARY KEY,
    raw_article_id BIGINT NOT NULL,
    metric_uid TEXT NOT NULL,
    source_type VARCHAR(50) NOT NULL,
    source_name VARCHAR(100),
    peer_id VARCHAR(50),
    period TEXT,
    period_year INT,
    period_quarter INT,
    period_type TEXT,
    metric_name TEXT NOT NULL,
    metric_label TEXT,
    metric_scope TEXT,
    business_area TEXT,
    value_numeric NUMERIC(24, 6),
    value_krwbn DOUBLE PRECISION,
    value_krw NUMERIC(24, 2),
    unit TEXT,
    currency VARCHAR(10) DEFAULT 'KRW',
    source_page INT,
    source_table_uid TEXT,
    source_chunk_uid TEXT,
    confidence DOUBLE PRECISION,
    extraction_method TEXT,
    evidence_text TEXT,
    payload JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_raw_article_financial_metrics_article
        FOREIGN KEY (raw_article_id) REFERENCES raw_articles(id) ON DELETE CASCADE,
    CONSTRAINT uq_raw_article_financial_metric
        UNIQUE (raw_article_id, metric_uid),
    CONSTRAINT chk_raw_article_financial_metrics_confidence
        CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1))
);

CREATE INDEX IF NOT EXISTS idx_raw_article_financial_metrics_peer_period
    ON raw_article_financial_metrics (peer_id, period);

CREATE INDEX IF NOT EXISTS idx_raw_article_financial_metrics_metric
    ON raw_article_financial_metrics (metric_name, period);

CREATE INDEX IF NOT EXISTS idx_raw_article_financial_metrics_source
    ON raw_article_financial_metrics (source_type, source_name);

CREATE INDEX IF NOT EXISTS idx_raw_article_financial_metrics_payload_gin
    ON raw_article_financial_metrics USING GIN(payload);

CREATE TABLE IF NOT EXISTS raw_article_business_signals (
    id BIGSERIAL PRIMARY KEY,
    raw_article_id BIGINT NOT NULL,
    signal_uid TEXT NOT NULL,
    source_type VARCHAR(50) NOT NULL,
    source_name VARCHAR(100),
    peer_id VARCHAR(50),
    period TEXT,
    period_year INT,
    period_quarter INT,
    period_type TEXT,
    business_area TEXT NOT NULL,
    signal_type TEXT NOT NULL,
    sentiment TEXT,
    summary TEXT NOT NULL,
    evidence_text TEXT,
    source_page INT,
    source_chunk_uid TEXT,
    confidence DOUBLE PRECISION,
    extraction_method TEXT,
    payload JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_raw_article_business_signals_article
        FOREIGN KEY (raw_article_id) REFERENCES raw_articles(id) ON DELETE CASCADE,
    CONSTRAINT uq_raw_article_business_signal
        UNIQUE (raw_article_id, signal_uid),
    CONSTRAINT chk_raw_article_business_signals_confidence
        CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
    CONSTRAINT chk_raw_article_business_signals_sentiment
        CHECK (
            sentiment IS NULL
            OR sentiment IN ('positive', 'neutral', 'negative', 'mixed', 'unknown')
        )
);

CREATE INDEX IF NOT EXISTS idx_raw_article_business_signals_peer_period
    ON raw_article_business_signals (peer_id, period);

CREATE INDEX IF NOT EXISTS idx_raw_article_business_signals_area_type
    ON raw_article_business_signals (business_area, signal_type);

CREATE INDEX IF NOT EXISTS idx_raw_article_business_signals_source
    ON raw_article_business_signals (source_type, source_name);

CREATE INDEX IF NOT EXISTS idx_raw_article_business_signals_payload_gin
    ON raw_article_business_signals USING GIN(payload);

CREATE OR REPLACE FUNCTION axis_touch_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_raw_article_financial_metrics_updated_at
    ON raw_article_financial_metrics;

CREATE TRIGGER trg_raw_article_financial_metrics_updated_at
BEFORE UPDATE ON raw_article_financial_metrics
FOR EACH ROW
EXECUTE FUNCTION axis_touch_updated_at();

DROP TRIGGER IF EXISTS trg_raw_article_business_signals_updated_at
    ON raw_article_business_signals;

CREATE TRIGGER trg_raw_article_business_signals_updated_at
BEFORE UPDATE ON raw_article_business_signals
FOR EACH ROW
EXECUTE FUNCTION axis_touch_updated_at();

COMMENT ON TABLE raw_article_financial_metrics IS
    'IR/DART 등 문서형 raw article에서 추출한 숫자형 재무/운영 fact.';

COMMENT ON TABLE raw_article_business_signals IS
    'IR/DART 등 문서형 raw article에서 추출한 사업영역/전략/리스크 정성 신호.';

