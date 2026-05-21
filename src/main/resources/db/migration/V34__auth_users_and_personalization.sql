-- V34: auth users + user-scoped personalization.
--
-- 설계 기준:
--   * users.role = USER/ADMIN 단일 컬럼으로 권한을 관리한다.
--   * 이메일 인증 토큰과 refresh token은 auth_tokens 한 테이블에 통합한다.
--   * 자동 로그인은 방문 기록이 아니라 HttpOnly refresh cookie + DB 저장 token_hash로만 판단한다.
--   * 프론트에서 사용자별로 달라지는 북마크, 알림, 설정, 접속 로그, 믹서 결과 소유자를 users에 연결한다.

CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    email VARCHAR(320) NOT NULL,
    email_domain VARCHAR(120) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    role VARCHAR(20) NOT NULL DEFAULT 'USER',
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    failed_login_count INT NOT NULL DEFAULT 0,
    locked_until TIMESTAMPTZ,
    last_login_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMPTZ,
    CONSTRAINT chk_users_status
        CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'WITHDRAWN')),
    CONSTRAINT chk_users_role
        CHECK (role IN ('USER', 'ADMIN')),
    CONSTRAINT chk_users_email_lowercase
        CHECK (email = lower(trim(email))),
    CONSTRAINT chk_users_email_domain_lowercase
        CHECK (email_domain = lower(trim(email_domain))),
    CONSTRAINT chk_users_failed_login_count
        CHECK (failed_login_count >= 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_users_email
    ON users (email);

CREATE INDEX IF NOT EXISTS idx_users_status
    ON users (status);

CREATE INDEX IF NOT EXISTS idx_users_role
    ON users (role);

DROP TRIGGER IF EXISTS trg_users_touch ON users;
CREATE TRIGGER trg_users_touch
BEFORE UPDATE ON users
FOR EACH ROW
EXECUTE FUNCTION axis_touch_updated_at();

CREATE TABLE IF NOT EXISTS auth_tokens (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type VARCHAR(30) NOT NULL,
    token_hash VARCHAR(128) NOT NULL,
    token_family_id UUID,
    previous_token_id UUID REFERENCES auth_tokens(id) ON DELETE SET NULL,
    user_agent TEXT,
    ip_address VARCHAR(64),
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_auth_tokens_type
        CHECK (type IN ('EMAIL_VERIFICATION', 'REFRESH')),
    CONSTRAINT chk_auth_tokens_refresh_family
        CHECK (
            (type = 'REFRESH' AND token_family_id IS NOT NULL)
            OR (type = 'EMAIL_VERIFICATION' AND token_family_id IS NULL)
        )
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_auth_tokens_hash
    ON auth_tokens (token_hash);

CREATE INDEX IF NOT EXISTS idx_auth_tokens_user_type
    ON auth_tokens (user_id, type);

CREATE INDEX IF NOT EXISTS idx_auth_tokens_family
    ON auth_tokens (token_family_id)
    WHERE token_family_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_auth_tokens_expires_at
    ON auth_tokens (expires_at);

CREATE TABLE IF NOT EXISTS user_settings (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    display_name VARCHAR(120),
    department VARCHAR(120),
    alert_rules JSONB NOT NULL DEFAULT '{}'::jsonb,
    notification_settings JSONB NOT NULL DEFAULT jsonb_build_object(
        'channels', jsonb_build_object('email', true, 'inApp', true, 'teams', false),
        'briefingTime', '08:30',
        'eventImmediate', true
    ),
    view_preferences JSONB NOT NULL DEFAULT '{}'::jsonb,
    security_settings JSONB NOT NULL DEFAULT jsonb_build_object(
        'refreshCookie', true,
        'autoLoginPolicy', 'refresh_token_only'
    ),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_user_settings_user
    ON user_settings (user_id);

DROP TRIGGER IF EXISTS trg_user_settings_touch ON user_settings;
CREATE TRIGGER trg_user_settings_touch
BEFORE UPDATE ON user_settings
FOR EACH ROW
EXECUTE FUNCTION axis_touch_updated_at();

CREATE TABLE IF NOT EXISTS user_card_news_bookmarks (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    card_news_id VARCHAR(50) NOT NULL REFERENCES card_news(id) ON DELETE CASCADE,
    note TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_user_card_news_bookmark
        UNIQUE (user_id, card_news_id)
);

CREATE INDEX IF NOT EXISTS idx_user_card_news_bookmarks_user
    ON user_card_news_bookmarks (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_user_card_news_bookmarks_card
    ON user_card_news_bookmarks (card_news_id);

CREATE TABLE IF NOT EXISTS user_notifications (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    notification_type VARCHAR(50) NOT NULL DEFAULT 'CARD_NEWS',
    title VARCHAR(300) NOT NULL,
    message TEXT,
    target_view VARCHAR(50),
    target_resource_id VARCHAR(100),
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_user_notifications_user_created
    ON user_notifications (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_user_notifications_user_unread
    ON user_notifications (user_id, created_at DESC)
    WHERE read_at IS NULL;

CREATE TABLE IF NOT EXISTS user_access_logs (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    action_type VARCHAR(60) NOT NULL,
    success BOOLEAN NOT NULL DEFAULT TRUE,
    ip_address VARCHAR(64),
    user_agent TEXT,
    session_id UUID,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_user_access_logs_action
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
            'PROFILE_UPDATED',
            'SETTINGS_UPDATED'
        ))
);

CREATE INDEX IF NOT EXISTS idx_user_access_logs_user_time
    ON user_access_logs (user_id, occurred_at DESC)
    WHERE user_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_user_access_logs_action_time
    ON user_access_logs (action_type, occurred_at DESC);

ALTER TABLE mixer_results
    ADD COLUMN IF NOT EXISTS user_id UUID;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_mixer_results_user'
          AND conrelid = 'mixer_results'::regclass
    ) THEN
        ALTER TABLE mixer_results
            ADD CONSTRAINT fk_mixer_results_user
            FOREIGN KEY (user_id)
            REFERENCES users(id)
            ON DELETE SET NULL;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_mixer_results_user_created
    ON mixer_results (user_id, created_at DESC)
    WHERE user_id IS NOT NULL;

COMMENT ON TABLE users IS
    'AXIS 회원 계정. role은 USER/ADMIN 단일 컬럼으로 관리하고 @sk.com은 가입 허용 조건일 뿐 관리자 조건이 아니다.';
COMMENT ON TABLE auth_tokens IS
    '이메일 인증 토큰과 refresh token 통합 저장소. 원문 token은 저장하지 않고 token_hash만 저장한다.';
COMMENT ON TABLE user_settings IS
    '사용자별 프로필 보조 정보, 알림 설정, 화면 선호도, 보안 설정.';
COMMENT ON TABLE user_card_news_bookmarks IS
    '사용자별 카드뉴스 북마크. 카드 목록과 믹서 입력의 개인화 기준으로 사용한다.';
COMMENT ON TABLE user_notifications IS
    '사용자별 in-app 알림 상태. 읽음 여부는 read_at으로 판단한다.';
COMMENT ON TABLE user_access_logs IS
    '로그인/로그아웃/refresh rotation 등 보안 접속 이력. 방문 기록만으로 자동 로그인하지 않는다.';
COMMENT ON COLUMN mixer_results.user_id IS
    '믹서 결과를 생성한 사용자. legacy requested_by_user_id(BIGINT)는 유지하고 신규 UUID 사용자 FK를 병행한다.';
