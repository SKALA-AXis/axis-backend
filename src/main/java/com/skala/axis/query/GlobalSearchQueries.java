package com.skala.axis.query;

public final class GlobalSearchQueries {
    public static final String BRIEFING_DATE = "COALESCE(br.report_date, br.date_to, br.date_from)";
    public static final String BRIEFING_SEARCH_TEXT = """
            concat_ws(
                ' ',
                br.title,
                br.briefing_type,
                br.period_label,
                br.key_summary,
                br.sk_implication,
                br.global_search_text,
                br.payload::text,
                br.legacy_payload::text,
                array_to_string(br.related_card_ids, ' '),
                br.provenance::text
            )
            """;
    public static final String CARD_NEWS_DATE = "cn.created_at::date";
    public static final String CARD_NEWS_SEARCH_TEXT = "cn.global_search_text";
    public static final String PEER_DATE = "COALESCE(pc.financial_updated_at, pc.created_at)::date";
    public static final String PEER_SEARCH_TEXT = "pc.global_search_text";

    public static String briefingSearchSql(String termMatchSql, String whereSql) {
        return """
                SELECT
                    br.id,
                    br.title,
                    COALESCE(NULLIF(br.key_summary, ''), NULLIF(br.sk_implication, ''), br.period_label, br.briefing_type) AS snippet,
                    COALESCE(br.report_date, br.date_to, br.date_from) AS item_date,
                    br.briefing_type,
                    br.status,
                    CASE
                        WHEN lower(br.title) = lower(?) THEN 130
                        WHEN %s = ? THEN 120
                        WHEN lower(br.title) LIKE lower(?) THEN 115
                        WHEN lower(COALESCE(br.key_summary, '')) LIKE lower(?) THEN 105
                        WHEN lower(COALESCE(br.sk_implication, '')) LIKE lower(?) THEN 100
                        WHEN %s ILIKE ? THEN 96
                        WHEN %s THEN 88
                        ELSE 45
                    END AS score
                FROM briefing_reports br
                WHERE %s
                ORDER BY score DESC, COALESCE(br.report_date, br.date_to, br.date_from) DESC, br.created_at DESC
                LIMIT ?
                """.formatted(BRIEFING_DATE, BRIEFING_SEARCH_TEXT, termMatchSql, whereSql);
    }

    public static String cardNewsHydrateSql(String placeholders, String dateFilter) {
        return """
                SELECT
                    cn.id,
                    cn.title,
                    COALESCE(NULLIF(cn.primary_keyword_category, ''), NULLIF(cn.event_type, ''), NULLIF(cn.importance, ''), '카드뉴스') AS subtitle,
                    COALESCE(pc.name, cn.company) AS company_name,
                    cn.created_at::date AS item_date,
                    cn.importance,
                    cn.event_type,
                    cn.primary_raw_article_id
                FROM card_news cn
                LEFT JOIN peer_companies pc ON pc.id = COALESCE(cn.peer_company_id, cn.company)
                WHERE cn.status = 'ACTIVE' AND cn.primary_raw_article_id IN (%s)%s
                """.formatted(placeholders, dateFilter);
    }

    public static String cardNewsKeywordSql(String whereSql) {
        return """
                SELECT
                    cn.id,
                    cn.title,
                    COALESCE(NULLIF(cn.primary_keyword_category, ''), NULLIF(cn.event_type, ''), NULLIF(cn.importance, ''), '카드뉴스') AS subtitle,
                    COALESCE(pc.name, cn.company) AS company_name,
                    cn.created_at::date AS item_date,
                    cn.importance,
                    cn.event_type,
                    CASE
                        WHEN lower(cn.title) = lower(?) THEN 110
                        WHEN lower(cn.title) LIKE lower(?) THEN 85
                        WHEN lower(array_to_string(cn.keywords, ' ')) LIKE lower(?) THEN 70
                        ELSE 40
                    END AS score
                FROM card_news cn
                LEFT JOIN peer_companies pc ON pc.id = COALESCE(cn.peer_company_id, cn.company)
                WHERE cn.status = 'ACTIVE' AND %s
                ORDER BY score DESC, cn.created_at DESC
                LIMIT ?
                """.formatted(whereSql);
    }

    public static String peerSearchSql(String whereSql) {
        return """
                SELECT
                    pc.id,
                    pc.name,
                    pc.tier,
                    COALESCE(array_to_string(pc.core_keywords, ', '), array_to_string(pc.keywords, ', '), pc.tier) AS snippet,
                    COALESCE(pc.financial_updated_at, pc.created_at)::date AS item_date,
                    CASE
                        WHEN lower(pc.name) = lower(?) OR lower(pc.id) = lower(?) THEN 100
                        WHEN lower(pc.name) LIKE lower(?) THEN 80
                        ELSE 38
                    END AS score
                FROM peer_companies pc
                WHERE %s
                ORDER BY score DESC, COALESCE(pc.financial_updated_at, pc.created_at) DESC
                LIMIT ?
                """.formatted(whereSql);
    }

    private GlobalSearchQueries() {
    }
}
