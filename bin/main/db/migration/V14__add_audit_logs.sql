-- V14: audit_logs — 변경불가 (append-only) 보안 감사 로그.
--
-- 담당 middleware: axis-infra/docs/AUDIT_LOG.md (AuditLogMiddleware, Spring AOP @Auditable)
-- API: GET /api/admin/audit-logs (super_admin) + GET /api/settings/access-logs (개인 본인)
--
-- 설계 결정:
--   · INSERT-only (UPDATE/DELETE 차단 trigger). 정보보안 정책 §4.2 / 개인정보보호법 §29 정합.
--   · RBAC 3-tier (super_admin / ops_admin / viewer) 적용은 BE Spring Security 책임.
--   · BE 측 admin / settings PUT / POST / DELETE 자동 + axis-ai 의 의도된 이벤트
--     (pipeline_run / briefing_generate / weak_signal_run).
--   · payload 의 PII 마스킹은 BE AOP 가 INSERT 전에 수행 (password / email / token 키).
--   · Retention 1년 (이후 cold storage 이관 cron — 별도 PR).

CREATE TABLE audit_logs (
    id              BIGSERIAL PRIMARY KEY,
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    actor_kind      VARCHAR(20) NOT NULL,                       -- user / system / scheduler / admin
    actor_label     VARCHAR(200),                               -- email or scheduler-name
    user_id         BIGINT,                                     -- nullable: system / anonymous
    action_type     VARCHAR(80) NOT NULL,                       -- pipeline_run / settings.update / auth.login / alert_rule.update / ...
    resource_type   VARCHAR(40),                                -- card_news / alert_rule / peer / briefing / ...
    resource_id     VARCHAR(80),
    ip              INET,
    user_agent      TEXT,
    payload         JSONB NOT NULL DEFAULT '{}'::jsonb,         -- {filters, before, after} — PII 마스킹 후
    request_id      UUID,                                       -- BE MDC trace id (Langfuse / usage_logs 와 join)
    outcome         VARCHAR(20) NOT NULL DEFAULT 'success',     -- success / fail / denied
    error_message   TEXT,
    CONSTRAINT audit_logs_actor_kind_check
        CHECK (actor_kind IN ('user', 'system', 'scheduler', 'admin')),
    CONSTRAINT audit_logs_outcome_check
        CHECK (outcome IN ('success', 'fail', 'denied'))
);

CREATE INDEX idx_audit_user_date
    ON audit_logs(user_id, occurred_at DESC)
    WHERE user_id IS NOT NULL;

CREATE INDEX idx_audit_action_date
    ON audit_logs(action_type, occurred_at DESC);

CREATE INDEX idx_audit_resource
    ON audit_logs(resource_type, resource_id)
    WHERE resource_type IS NOT NULL;

CREATE INDEX idx_audit_request_id
    ON audit_logs(request_id)
    WHERE request_id IS NOT NULL;

-- INSERT-only enforcement — UPDATE / DELETE 차단
CREATE OR REPLACE FUNCTION audit_logs_block_change() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit_logs 는 append-only — UPDATE / DELETE 금지 (정보보안 정책 §4.2)';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_logs_block_update
    BEFORE UPDATE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION audit_logs_block_change();

CREATE TRIGGER trg_audit_logs_block_delete
    BEFORE DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION audit_logs_block_change();

COMMENT ON TABLE audit_logs IS '변경불가 보안 감사 로그. AuditAspect (Spring AOP) + axis-ai @audit decorator 가 INSERT.';
COMMENT ON COLUMN audit_logs.request_id IS 'BE MDC 의 trace id — usage_logs / chat_turns 의 동일 컬럼과 grep / join 가능';
COMMENT ON COLUMN audit_logs.payload IS 'PII 마스킹 후 jsonb (password / email / token 키는 *** 로 치환됨)';
