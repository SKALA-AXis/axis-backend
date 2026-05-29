-- V39: allow users to customize important-signal keywords.

ALTER TABLE users
    ALTER COLUMN notification_preferences SET DEFAULT '{
      "enabled": true,
      "importantEnabled": true,
      "importantKeywords": ["수주", "계약", "실적", "투자"],
      "keywords": []
    }'::jsonb;

UPDATE users
SET notification_preferences = jsonb_set(
        COALESCE(notification_preferences, '{}'::jsonb),
        '{importantKeywords}',
        '["수주", "계약", "실적", "투자"]'::jsonb,
        true
    )
WHERE notification_preferences IS NULL
   OR NOT (notification_preferences ? 'importantKeywords');

COMMENT ON COLUMN users.notification_preferences IS
    '사용자별 알림 선호도. JSON key: enabled, importantEnabled, importantKeywords, keywords.';
