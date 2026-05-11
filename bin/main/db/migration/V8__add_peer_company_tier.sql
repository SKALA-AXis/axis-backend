-- ============================================================
-- V8 — peer_companies tier 도입
--
-- self: 자사, domestic: 국내 기업, overseas: 해외 기업
-- ============================================================

ALTER TABLE peer_companies
    ADD COLUMN IF NOT EXISTS tier VARCHAR(20) NOT NULL DEFAULT 'domestic';

UPDATE peer_companies
SET tier = CASE
    WHEN id = 'sk_ax' THEN 'self'
    ELSE 'domestic'
END
WHERE tier IS NULL
   OR tier NOT IN ('self', 'domestic', 'overseas')
   OR id IN ('sk_ax', 'samsung_sds', 'lg_cns', 'hyundai_autoever', 'posco_dx');

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_peer_companies_tier'
          AND conrelid = 'peer_companies'::regclass
    ) THEN
        ALTER TABLE peer_companies
            ADD CONSTRAINT chk_peer_companies_tier
            CHECK (tier IN ('self', 'domestic', 'overseas'));
    END IF;
END $$;
