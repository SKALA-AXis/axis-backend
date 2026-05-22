-- V35: notification MVP alignment.
-- Keep the existing user_notifications table name, but extend it with the
-- document-level notification fields and soft-delete/read policies.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS notification_preferences JSONB NOT NULL DEFAULT '{
      "enabled": true,
      "importantEnabled": true,
      "keywords": []
    }'::jsonb;

ALTER TABLE user_notifications
    ADD COLUMN IF NOT EXISTS severity VARCHAR(20) NOT NULL DEFAULT 'NORMAL',
    ADD COLUMN IF NOT EXISTS source_type VARCHAR(50) NOT NULL DEFAULT 'IN_APP',
    ADD COLUMN IF NOT EXISTS source_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS source_url VARCHAR(500),
    ADD COLUMN IF NOT EXISTS company_name VARCHAR(100),
    ADD COLUMN IF NOT EXISTS matched_keywords JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS dedupe_key VARCHAR(200),
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

UPDATE user_notifications
SET dedupe_key = COALESCE(dedupe_key, 'legacy:' || id::text),
    updated_at = COALESCE(updated_at, created_at, NOW())
WHERE dedupe_key IS NULL
   OR updated_at IS NULL;

ALTER TABLE user_notifications
    ALTER COLUMN dedupe_key SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_user_notifications_severity'
          AND conrelid = 'user_notifications'::regclass
    ) THEN
        ALTER TABLE user_notifications
            ADD CONSTRAINT chk_user_notifications_severity
            CHECK (severity IN ('NORMAL', 'IMPORTANT'));
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uk_user_notifications_user_dedupe
    ON user_notifications (user_id, dedupe_key);

CREATE INDEX IF NOT EXISTS idx_user_notifications_user_created_active
    ON user_notifications (user_id, created_at DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_user_notifications_user_unread_active
    ON user_notifications (user_id, read_at)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_user_notifications_source
    ON user_notifications (source_type, source_id);

COMMENT ON COLUMN users.notification_preferences IS
    '사용자별 알림 선호도. JSON key: enabled, importantEnabled, keywords.';
COMMENT ON COLUMN user_notifications.dedupe_key IS
    '동일 사용자에게 같은 source/version/keyword 알림이 중복 생성되지 않도록 하는 키.';
COMMENT ON COLUMN user_notifications.deleted_at IS
    '알림 soft delete 시각. 목록과 unread count 에서는 제외한다.';
