-- V36: global search support for TopNav integrated search.
-- Keep denormalized text columns so JSON/array body search can use trigram indexes.

CREATE EXTENSION IF NOT EXISTS "pg_trgm";

ALTER TABLE card_news
    ADD COLUMN IF NOT EXISTS global_search_text TEXT NOT NULL DEFAULT '';

ALTER TABLE briefing_reports
    ADD COLUMN IF NOT EXISTS global_search_text TEXT NOT NULL DEFAULT '';

ALTER TABLE peer_companies
    ADD COLUMN IF NOT EXISTS global_search_text TEXT NOT NULL DEFAULT '';

UPDATE card_news
SET global_search_text = concat_ws(
    ' ',
    title,
    company,
    peer_company_id,
    event_type,
    importance,
    primary_keyword_category,
    array_to_string(keywords, ' '),
    implication::text,
    keyword_categories::text,
    keyword_frequency::text,
    evidence_payload::text,
    source_articles::text
)
WHERE global_search_text = '';

UPDATE briefing_reports
SET global_search_text = concat_ws(
    ' ',
    title,
    briefing_type,
    period_label,
    key_summary,
    sk_implication,
    payload::text,
    legacy_payload::text,
    array_to_string(related_card_ids, ' ')
)
WHERE global_search_text = '';

UPDATE peer_companies
SET global_search_text = concat_ws(
    ' ',
    id,
    name,
    tier,
    dart_period,
    array_to_string(keywords, ' '),
    array_to_string(core_keywords, ' '),
    peer_plus_payload::text,
    legacy_payload::text
)
WHERE global_search_text = '';

CREATE OR REPLACE FUNCTION axis_refresh_card_news_global_search_text()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.global_search_text := concat_ws(
        ' ',
        NEW.title,
        NEW.company,
        NEW.peer_company_id,
        NEW.event_type,
        NEW.importance,
        NEW.primary_keyword_category,
        array_to_string(NEW.keywords, ' '),
        NEW.implication::text,
        NEW.keyword_categories::text,
        NEW.keyword_frequency::text,
        NEW.evidence_payload::text,
        NEW.source_articles::text
    );
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION axis_refresh_briefing_reports_global_search_text()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.global_search_text := concat_ws(
        ' ',
        NEW.title,
        NEW.briefing_type,
        NEW.period_label,
        NEW.key_summary,
        NEW.sk_implication,
        NEW.payload::text,
        NEW.legacy_payload::text,
        array_to_string(NEW.related_card_ids, ' ')
    );
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION axis_refresh_peer_companies_global_search_text()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.global_search_text := concat_ws(
        ' ',
        NEW.id,
        NEW.name,
        NEW.tier,
        NEW.dart_period,
        array_to_string(NEW.keywords, ' '),
        array_to_string(NEW.core_keywords, ' '),
        NEW.peer_plus_payload::text,
        NEW.legacy_payload::text
    );
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_card_news_global_search_text ON card_news;
CREATE TRIGGER trg_card_news_global_search_text
BEFORE INSERT OR UPDATE OF title, company, peer_company_id, event_type, importance, primary_keyword_category,
    keywords, implication, keyword_categories, keyword_frequency, evidence_payload, source_articles
ON card_news
FOR EACH ROW
EXECUTE FUNCTION axis_refresh_card_news_global_search_text();

DROP TRIGGER IF EXISTS trg_briefing_reports_global_search_text ON briefing_reports;
CREATE TRIGGER trg_briefing_reports_global_search_text
BEFORE INSERT OR UPDATE OF title, briefing_type, period_label, key_summary, sk_implication,
    payload, legacy_payload, related_card_ids
ON briefing_reports
FOR EACH ROW
EXECUTE FUNCTION axis_refresh_briefing_reports_global_search_text();

DROP TRIGGER IF EXISTS trg_peer_companies_global_search_text ON peer_companies;
CREATE TRIGGER trg_peer_companies_global_search_text
BEFORE INSERT OR UPDATE OF id, name, tier, dart_period, keywords, core_keywords, peer_plus_payload, legacy_payload
ON peer_companies
FOR EACH ROW
EXECUTE FUNCTION axis_refresh_peer_companies_global_search_text();

CREATE INDEX IF NOT EXISTS idx_card_news_global_search_trgm
    ON card_news USING GIN (global_search_text gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_briefing_reports_global_search_trgm
    ON briefing_reports USING GIN (global_search_text gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_peer_companies_global_search_trgm
    ON peer_companies USING GIN (global_search_text gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_card_news_created_desc
    ON card_news (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_briefing_reports_report_date_desc
    ON briefing_reports (COALESCE(report_date, date_to, date_from) DESC, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_peer_companies_updated_desc
    ON peer_companies (COALESCE(financial_updated_at, created_at) DESC);
