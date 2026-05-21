-- V33: Context Engineering 인덱스 + VIEW + MATERIALIZED VIEW.
--
-- 본 마이그레이션은 axis-ai 의 1단계 분석 Supervisor (`design/01-supervisor-implementation-plan.md`)
-- 의 W4 단계 (Context Engineering) 와 W5 단계 (Evaluation) 에서 사용한다.
-- 신규 테이블은 0개. 컬럼 2 + 인덱스 4 + VIEW 2 + MATERIALIZED VIEW 1.
--
-- 의존성: V32_5__card_news_backfill.sql 가 먼저 적용되어야 peer_company_id /
-- primary_keyword_category NULL 비율이 0 으로 회복된다.

-- ─────────────────────────────────────────────────────────────────────────────
-- (a0) W2-2: peer_companies.profile_snapshot (Tier A, 주1회 LLM 합성)
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE peer_companies
    ADD COLUMN IF NOT EXISTS profile_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS profile_snapshot_version VARCHAR(50),
    ADD COLUMN IF NOT EXISTS profile_snapshot_generated_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_peer_companies_snapshot_version
    ON peer_companies(profile_snapshot_version);

COMMENT ON COLUMN peer_companies.profile_snapshot IS
    'Tier A static profile snapshot. axis-cron-profile-refresh 가 주 1회 LLM 합성하여 갱신. JSONB key: narrative / core_capabilities / recent_keywords / business_lines / 등.';
COMMENT ON COLUMN peer_companies.profile_snapshot_version IS
    'Snapshot 의 prompt version (예: profile-v5).';
COMMENT ON COLUMN peer_companies.profile_snapshot_generated_at IS
    'Snapshot 갱신 시각 (CronJob 실행 시 갱신).';

-- ─────────────────────────────────────────────────────────────────────────────
-- (a) W1-4: card_schema_version 컬럼 — v1 / v2 카드 식별
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE card_news
    ADD COLUMN IF NOT EXISTS card_schema_version VARCHAR(10) NOT NULL DEFAULT 'v1';

CREATE INDEX IF NOT EXISTS idx_card_news_schema_version
    ON card_news(card_schema_version, created_at DESC);

COMMENT ON COLUMN card_news.card_schema_version IS
    'Card schema generation: v1=legacy heuristic implication metadata, v2=요약+시사점+대응 (LLM, W1-1 이후).';

-- ─────────────────────────────────────────────────────────────────────────────
-- (a-2) W5-1: evaluation_payload JSONB — rule-based + LLM-as-Judge 결과 누적
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE card_news
    ADD COLUMN IF NOT EXISTS evaluation_payload JSONB NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN card_news.evaluation_payload IS
    'W5 evaluation results. Keys: rule_based (W5-1, 4-5 metric in-graph), llm_judge (W5-2, 4 score sidecar). v1 카드는 항상 빈 JSONB.';

-- Sidecar SELECT 가드 (v2 + llm_judge 미평가) 가 partial index 로 빠르게 동작.
CREATE INDEX IF NOT EXISTS idx_card_news_unjudged_v2
    ON card_news(created_at DESC)
    WHERE card_schema_version = 'v2'
      AND NOT (evaluation_payload ? 'llm_judge');

-- ─────────────────────────────────────────────────────────────────────────────
-- (b) Layer 2 인덱스 3개 — §3.4.3
-- ─────────────────────────────────────────────────────────────────────────────

-- peer 별 시간순 카드 hot path (cluster_id 사용 금지 — ephemeral)
CREATE INDEX IF NOT EXISTS idx_card_news_peer_created
    ON card_news(peer_company_id, created_at DESC)
    WHERE peer_company_id IS NOT NULL;

-- capability narrative source: peer × business_area × period 그룹화
CREATE INDEX IF NOT EXISTS idx_business_signals_peer_area_period
    ON raw_article_business_signals(peer_id, business_area, period_year DESC, period_quarter DESC NULLS LAST);

-- financial trend: peer × metric × period 시계열
CREATE INDEX IF NOT EXISTS idx_financial_metrics_peer_metric_period
    ON raw_article_financial_metrics(peer_id, metric_name, period_year DESC, period_quarter DESC NULLS LAST);

-- ─────────────────────────────────────────────────────────────────────────────
-- (c) Layer 2-A: peer_event_timeline VIEW
--     ephemeral cluster_id 는 노출하지 않는다. peer + date + card_id 만 join key.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE OR REPLACE VIEW peer_event_timeline AS
SELECT
    cn.peer_company_id              AS company_id,
    DATE(cn.created_at)             AS event_date,
    cn.id                           AS card_id,
    cn.event_type,
    cn.primary_keyword_category     AS sector,
    cn.title                        AS headline,
    cn.summary_lines,
    cn.keyword_categories           AS capability_areas,
    cn.importance,
    cn.importance_score,
    cn.source_raw_article_ids       AS evidence_article_ids,
    cn.card_schema_version
FROM card_news cn
WHERE cn.peer_company_id IS NOT NULL
  AND cn.created_at >= NOW() - INTERVAL '180 days';

COMMENT ON VIEW peer_event_timeline IS
    'Layer 2-A: peer 별 카드 시계열 hot view (180일). cluster_id 미노출 — ephemeral 이므로 join 금지.';

-- ─────────────────────────────────────────────────────────────────────────────
-- (c-2) general_event_timeline VIEW — peer 매칭 안 된 sector-only 카드 fallback.
--       (P3-DATA-8) 191 카드 중 31% 가 peer NULL.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE OR REPLACE VIEW general_event_timeline AS
SELECT
    DATE(cn.created_at)             AS event_date,
    cn.id                           AS card_id,
    cn.event_type,
    cn.primary_keyword_category     AS sector,
    cn.title                        AS headline,
    cn.summary_lines,
    cn.importance,
    cn.importance_score,
    cn.card_schema_version
FROM card_news cn
WHERE cn.peer_company_id IS NULL
  AND cn.primary_keyword_category IS NOT NULL
  AND cn.created_at >= NOW() - INTERVAL '180 days';

COMMENT ON VIEW general_event_timeline IS
    'Layer 2-A supplement: peer 매칭 없이 sector 만 있는 카드 (P3-DATA-8 의 31%) 의 fallback view.';

-- ─────────────────────────────────────────────────────────────────────────────
-- (d) Layer 2-C: sector_pulse MATERIALIZED VIEW — 주 1회 REFRESH
--     Phase 1: event_count / intensity / event_type_distribution / active_peers.
--     Phase 2 (운영 4주 후): z-score 등 anomaly 컬럼 별도 ALTER 로 활성화.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE MATERIALIZED VIEW IF NOT EXISTS sector_pulse AS
SELECT
    COALESCE(cn.primary_keyword_category, 'general')        AS sector,
    DATE_TRUNC('week', cn.created_at)::DATE                 AS week_start,
    COUNT(*)                                                AS event_count,
    COUNT(*) FILTER (WHERE cn.peer_company_id IS NOT NULL)  AS peer_event_count,
    COUNT(*) FILTER (WHERE cn.peer_company_id IS NULL)      AS general_event_count,
    AVG(cn.importance_score)                                AS intensity_avg,
    jsonb_object_agg(
        DISTINCT cn.event_type,
        (
            SELECT COUNT(*)
            FROM card_news cn2
            WHERE cn2.event_type = cn.event_type
              AND DATE_TRUNC('week', cn2.created_at) = DATE_TRUNC('week', cn.created_at)
              AND COALESCE(cn2.primary_keyword_category, 'general')
                  = COALESCE(cn.primary_keyword_category, 'general')
        )
    )                                                       AS event_type_distribution,
    array_remove(array_agg(DISTINCT cn.peer_company_id), NULL)
                                                            AS active_peers,
    (
        array_agg(cn.id ORDER BY cn.importance_score DESC NULLS LAST)
        FILTER (WHERE cn.importance_score >= 0.7)
    )[1:5]                                                  AS notable_card_ids
FROM card_news cn
WHERE cn.created_at >= NOW() - INTERVAL '180 days'
GROUP BY 1, 2;

CREATE UNIQUE INDEX IF NOT EXISTS uq_sector_pulse_sector_week
    ON sector_pulse(sector, week_start);

COMMENT ON MATERIALIZED VIEW sector_pulse IS
    'Layer 2-C: 섹터 × 주차 카드 집계. 주 1회 REFRESH (axis-cron-sector-pulse). Phase 1: count + intensity 만.';

-- ─────────────────────────────────────────────────────────────────────────────
-- (e) Layer 2-D: peer_financial_trend VIEW — metric_name 정규화 + period_quarter NULL fallback
-- ─────────────────────────────────────────────────────────────────────────────
CREATE OR REPLACE VIEW peer_financial_trend AS
SELECT
    rfm.peer_id                                              AS company_id,
    CASE
        WHEN rfm.metric_name IN ('net_income', '당기순이익', '순이익', 'net_profit', '지배주주순이익') THEN 'net_income'
        WHEN rfm.metric_name IN ('revenue_total', 'revenue', '매출', '매출액', '총매출') THEN 'revenue_total'
        WHEN rfm.metric_name IN ('operating_profit', '영업이익', 'operating_income') THEN 'operating_profit'
        WHEN rfm.metric_name IN ('operating_margin', '영업이익률') THEN 'operating_margin'
        WHEN rfm.metric_name IN ('gross_profit', '매출총이익') THEN 'gross_profit'
        WHEN rfm.metric_name IN ('employees', '임직원수', '직원수') THEN 'employees'
        ELSE rfm.metric_name
    END                                                      AS metric_name_canonical,
    rfm.metric_name                                          AS metric_name_raw,
    rfm.metric_label,
    rfm.business_area,
    rfm.period_year,
    COALESCE(rfm.period_quarter::text, 'annual')             AS period_quarter_safe,
    rfm.period_quarter,
    rfm.period,
    rfm.value_numeric,
    rfm.value_krwbn,
    rfm.unit,
    rfm.currency,
    rfm.confidence,
    rfm.raw_article_id                                       AS evidence_article_id
FROM raw_article_financial_metrics rfm
WHERE rfm.peer_id IS NOT NULL;

COMMENT ON VIEW peer_financial_trend IS
    'Layer 2-D: peer 별 재무 metric 시계열 (canonical 매핑 + period_quarter NULL fallback).';

-- ─────────────────────────────────────────────────────────────────────────────
-- (f) peer_companies.peer_plus_payload JSONB key namespace 표준 코멘트
-- ─────────────────────────────────────────────────────────────────────────────
COMMENT ON COLUMN peer_companies.peer_plus_payload IS
    'JSONB namespaces (axis-ai design v3.2): profile_snapshot (W2-2 weekly LLM), capability_evolution (W4-3 monthly LLM), snapshot_archive_ref (legacy_records 메타). See design/01-supervisor-implementation-plan.md §3.4.3.';
