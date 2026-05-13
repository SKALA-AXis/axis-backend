-- V18: infra_cost_daily — AWS Cost Explorer 의 인프라 청구 비용 동기화.
--
-- 담당 spec: axis-infra/docs/admin/cost_reconciliation.md
-- Trigger: Spring @Scheduled 매일 03:00 KST → AWS GetCostAndUsage (Daily, GroupBy=SERVICE)
-- IRSA SA: cost-explorer-reader-sa (ce:GetCostAndUsage 권한만 — least privilege)
--
-- 인프라 / 모델 비용을 1 dashboard 에 통합 표시 (admin_page §5 Folder C FinOps).

CREATE TABLE infra_cost_daily (
    id           BIGSERIAL PRIMARY KEY,
    date         DATE NOT NULL,
    service      VARCHAR(80) NOT NULL,                              -- AWS service code (e.g. 'Amazon Elastic Compute Cloud - Compute')
    resource     VARCHAR(120),                                      -- nullable: service 합계 또는 EC2 instance / RDS db / etc.
    cost_usd     NUMERIC(12,4) NOT NULL,
    fetched_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    raw_payload  JSONB,                                             -- 원본 API 응답 (audit)
    -- 의도: (date, service, resource=NULL) = service 합계 row (1개만 허용).
    -- PG default 'NULLS DISTINCT' 로는 NULL resource 가 여러 개 허용되어 의도 위배 →
    -- 'NULLS NOT DISTINCT' (PG 15+) 명시. 본 cluster = PG 16.13 검증 완료 (2026-05-13).
    CONSTRAINT infra_cost_unique
        UNIQUE NULLS NOT DISTINCT (date, service, resource),
    CONSTRAINT infra_cost_nonneg
        CHECK (cost_usd >= 0)
);

CREATE INDEX idx_infra_cost_date
    ON infra_cost_daily(date DESC, service);

CREATE INDEX idx_infra_cost_service_date
    ON infra_cost_daily(service, date DESC);

COMMENT ON TABLE infra_cost_daily IS 'AWS Cost Explorer 의 인프라 청구 비용 (일별 service 그룹). cost_daily_billed (V17) 와 함께 admin_page §5 FinOps 폴더 source.';
COMMENT ON COLUMN infra_cost_daily.service IS 'AWS service code (예: EKS, EC2-Compute, RDS, S3, ELB)';
COMMENT ON COLUMN infra_cost_daily.resource IS 'nullable — null 이면 service 단위 합계. 값 있으면 individual resource (instance id 등)';
