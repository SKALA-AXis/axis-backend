package com.skala.axis.query;

public final class KeywordGraphQueries {
    public static final String KEYWORD_RELATIONS_SQL = """
            WITH keyword_rows AS (
                SELECT
                    ra.id AS raw_article_id,
                    lower(regexp_replace(trim(k.keyword), '\\s+', ' ', 'g')) AS keyword_key,
                    trim(k.keyword) AS keyword,
                    c.company_id
                FROM raw_articles ra
                CROSS JOIN LATERAL (
                    SELECT detail->>'keyword' AS keyword
                    FROM jsonb_array_elements(
                        CASE
                            WHEN jsonb_typeof(ra.matched_sector_details) = 'array'
                            THEN ra.matched_sector_details
                            ELSE '[]'::jsonb
                        END
                    ) detail
                    WHERE detail ? 'keyword'
                      AND NULLIF(trim(detail->>'keyword'), '') IS NOT NULL
                ) k
                CROSS JOIN LATERAL (
                    SELECT DISTINCT
                        CASE
                            WHEN lower(regexp_replace(trim(company_token), '\\s+', '', 'g')) IN ('sk_ax', 'skaxis', 'skax', 'sk에이엑스', '에스케이에이엑스', 'skc&c', 'sk㈜c&c', 'sk주식회사c&c', '에스케이씨앤씨') THEN 'sk_ax'
                            WHEN lower(regexp_replace(trim(company_token), '\\s+', '', 'g')) IN ('samsung_sds', 'samsungsds', '삼성sds', '삼성에스디에스') THEN 'samsung_sds'
                            WHEN lower(regexp_replace(trim(company_token), '\\s+', '', 'g')) IN ('lg_cns', 'lgcns', '엘지씨엔에스') THEN 'lg_cns'
                            WHEN lower(regexp_replace(trim(company_token), '\\s+', '', 'g')) IN ('hyundai_autoever', 'hyundaiautoever', '현대오토에버') THEN 'hyundai_autoever'
                            WHEN lower(regexp_replace(trim(company_token), '\\s+', '', 'g')) IN ('posco_dx', 'poscodx', '포스코dx', '포스코디엑스', '포스코ict', 'poscoict') THEN 'posco_dx'
                            ELSE NULL
                        END AS company_id
                    FROM (
                        SELECT
                            CASE
                                WHEN jsonb_typeof(token.value) = 'object'
                                THEN COALESCE(token.value->>'id', token.value->>'peer_company_id', token.value->>'name', token.value->>'company')
                                ELSE trim(token.value #>> '{}')
                            END AS company_token
                        FROM jsonb_array_elements(
                            CASE
                                WHEN jsonb_typeof(ra.matched_companies) = 'array'
                                THEN ra.matched_companies
                                ELSE '[]'::jsonb
                            END
                        ) token
                        UNION ALL
                        SELECT
                            CASE
                                WHEN jsonb_typeof(token.value) = 'object'
                                THEN COALESCE(token.value->>'id', token.value->>'peer_company_id', token.value->>'name', token.value->>'company')
                                ELSE trim(token.value #>> '{}')
                            END AS company_token
                        FROM jsonb_array_elements(
                            CASE
                                WHEN jsonb_typeof(ra.company) = 'array'
                                THEN ra.company
                                ELSE '[]'::jsonb
                            END
                        ) token
                    ) company_tokens
                ) c
                WHERE k.keyword IS NOT NULL
                  AND length(trim(k.keyword)) >= 2
                  AND c.company_id IS NOT NULL
            )
            SELECT company_id, keyword_key, MIN(keyword) AS keyword, COUNT(DISTINCT raw_article_id) AS weight
            FROM keyword_rows
            GROUP BY company_id, keyword_key
            ORDER BY weight DESC, keyword ASC
            """;

    public static final String CARD_NEWS_IDS_BY_RAW_KEYWORD_SQL = """
            WITH matched_raw AS (
                SELECT DISTINCT ra.id
                FROM raw_articles ra
                CROSS JOIN LATERAL jsonb_array_elements(
                    CASE
                        WHEN jsonb_typeof(ra.matched_sector_details) = 'array'
                        THEN ra.matched_sector_details
                        ELSE '[]'::jsonb
                    END
                ) detail
                WHERE lower(regexp_replace(trim(detail->>'keyword'), '\\s+', '', 'g')) = ?
            )
            SELECT DISTINCT cn.id, cn.created_at
            FROM card_news cn
            JOIN matched_raw mr
              ON cn.primary_raw_article_id = mr.id
              OR mr.id = ANY(COALESCE(cn.source_raw_article_ids, ARRAY[]::bigint[]))
            WHERE cn.status = 'ACTIVE'
            ORDER BY cn.created_at DESC
            """;

    public static final String CARD_NEWS_IDS_BY_RAW_COMPANY_SQL = """
            WITH matched_raw AS (
                SELECT DISTINCT ra.id
                FROM raw_articles ra
                CROSS JOIN LATERAL (
                    SELECT DISTINCT
                        CASE
                            WHEN lower(regexp_replace(trim(company_token), '\\s+', '', 'g')) IN ('sk_ax', 'skaxis', 'skax', 'sk에이엑스', '에스케이에이엑스', 'skc&c', 'sk㈜c&c', 'sk주식회사c&c', '에스케이씨앤씨') THEN 'sk_ax'
                            WHEN lower(regexp_replace(trim(company_token), '\\s+', '', 'g')) IN ('samsung_sds', 'samsungsds', '삼성sds', '삼성에스디에스') THEN 'samsung_sds'
                            WHEN lower(regexp_replace(trim(company_token), '\\s+', '', 'g')) IN ('lg_cns', 'lgcns', '엘지씨엔에스') THEN 'lg_cns'
                            WHEN lower(regexp_replace(trim(company_token), '\\s+', '', 'g')) IN ('hyundai_autoever', 'hyundaiautoever', '현대오토에버') THEN 'hyundai_autoever'
                            WHEN lower(regexp_replace(trim(company_token), '\\s+', '', 'g')) IN ('posco_dx', 'poscodx', '포스코dx', '포스코디엑스', '포스코ict', 'poscoict') THEN 'posco_dx'
                            ELSE NULL
                        END AS company_id
                    FROM (
                        SELECT
                            CASE
                                WHEN jsonb_typeof(token.value) = 'object'
                                THEN COALESCE(token.value->>'id', token.value->>'peer_company_id', token.value->>'name', token.value->>'company')
                                ELSE trim(token.value #>> '{}')
                            END AS company_token
                        FROM jsonb_array_elements(
                            CASE
                                WHEN jsonb_typeof(ra.matched_companies) = 'array'
                                THEN ra.matched_companies
                                ELSE '[]'::jsonb
                            END
                        ) token
                        UNION ALL
                        SELECT
                            CASE
                                WHEN jsonb_typeof(token.value) = 'object'
                                THEN COALESCE(token.value->>'id', token.value->>'peer_company_id', token.value->>'name', token.value->>'company')
                                ELSE trim(token.value #>> '{}')
                            END AS company_token
                        FROM jsonb_array_elements(
                            CASE
                                WHEN jsonb_typeof(ra.company) = 'array'
                                THEN ra.company
                                ELSE '[]'::jsonb
                            END
                        ) token
                    ) company_tokens
                ) c
                WHERE c.company_id = ?
            )
            SELECT DISTINCT cn.id, cn.created_at
            FROM card_news cn
            JOIN matched_raw mr
              ON cn.primary_raw_article_id = mr.id
              OR mr.id = ANY(COALESCE(cn.source_raw_article_ids, ARRAY[]::bigint[]))
            WHERE cn.status = 'ACTIVE'
            ORDER BY cn.created_at DESC
            """;

    public static String rawArticleCardsSql(String whereSql) {
        return """
                SELECT id, title, url, publisher, published_at::text AS published_at, created_at::text AS created_at
                FROM raw_articles
                WHERE %s
                ORDER BY published_at DESC NULLS LAST, created_at DESC NULLS LAST, id DESC
                LIMIT ?
                """.formatted(whereSql);
    }

    private KeywordGraphQueries() {
    }
}
