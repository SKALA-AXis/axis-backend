ALTER TABLE card_news
    ADD COLUMN IF NOT EXISTS status VARCHAR(20);

UPDATE card_news
SET status = 'ACTIVE'
WHERE status IS NULL OR status = '';

ALTER TABLE card_news
    ALTER COLUMN status SET NOT NULL;

ALTER TABLE card_news
    ALTER COLUMN status SET DEFAULT 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_card_news_status_created_at
    ON card_news (status, created_at DESC);

CREATE TABLE IF NOT EXISTS admin_audit_logs (
    id              BIGSERIAL PRIMARY KEY,
    actor_user_id   UUID,
    actor_email     VARCHAR(320) NOT NULL,
    action_type     VARCHAR(80) NOT NULL,
    resource_type   VARCHAR(40) NOT NULL,
    resource_id     VARCHAR(80) NOT NULL,
    reason          TEXT,
    payload         JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_admin_audit_logs_created_at
    ON admin_audit_logs (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_admin_audit_logs_resource
    ON admin_audit_logs (resource_type, resource_id, created_at DESC);
