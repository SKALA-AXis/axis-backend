-- V37: password reset tokens reuse auth_tokens with the same hashed-token model.

ALTER TABLE auth_tokens
    DROP CONSTRAINT IF EXISTS chk_auth_tokens_type;

ALTER TABLE auth_tokens
    ADD CONSTRAINT chk_auth_tokens_type
        CHECK (type IN ('EMAIL_VERIFICATION', 'REFRESH', 'PASSWORD_RESET'));

ALTER TABLE auth_tokens
    DROP CONSTRAINT IF EXISTS chk_auth_tokens_refresh_family;

ALTER TABLE auth_tokens
    ADD CONSTRAINT chk_auth_tokens_refresh_family
        CHECK (
            (type = 'REFRESH' AND token_family_id IS NOT NULL)
            OR (type IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET') AND token_family_id IS NULL)
        );

ALTER TABLE user_access_logs
    DROP CONSTRAINT IF EXISTS chk_user_access_logs_action;

ALTER TABLE user_access_logs
    ADD CONSTRAINT chk_user_access_logs_action
        CHECK (action_type IN (
            'SIGNUP',
            'EMAIL_VERIFIED',
            'EMAIL_VERIFICATION_RESENT',
            'LOGIN_SUCCESS',
            'LOGIN_FAILURE',
            'LOGOUT',
            'REFRESH_ROTATED',
            'REFRESH_REUSE_DETECTED',
            'PASSWORD_CHANGED',
            'PASSWORD_RESET_REQUESTED',
            'PASSWORD_RESET_COMPLETED',
            'PASSWORD_RESET_FAILED',
            'PROFILE_UPDATED',
            'SETTINGS_UPDATED'
        ));

CREATE INDEX IF NOT EXISTS idx_auth_tokens_password_reset_active
    ON auth_tokens (user_id, expires_at DESC)
    WHERE type = 'PASSWORD_RESET'
      AND used_at IS NULL
      AND revoked_at IS NULL;
