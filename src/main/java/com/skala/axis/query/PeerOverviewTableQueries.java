package com.skala.axis.query;

public final class PeerOverviewTableQueries {
    public static final String LATEST_DATA_VERSION_SQL = """
            SELECT COALESCE(
                MAX(GREATEST(generated_at, created_at, updated_at)),
                TIMESTAMPTZ 'epoch'
            ) AS latest_at
            FROM peer_llm_analysis_snapshots
            WHERE analysis_type IN ('peer_swot_comparison', 'peer_overview_keywords')
              AND status = 'active'
              AND peer_id IN ('all', 'sk_ax', 'samsung_sds', 'lg_cns', 'hyundai_autoever', 'posco_dx')
              AND (expires_at IS NULL OR expires_at > NOW())
            """;

    public static final String SWOT_INSIGHTS_SQL = """
            WITH target_peers(id, name, aliases) AS (
                VALUES
                    ('sk_ax', 'SK AX', ARRAY['sk ax', 'skax', '에스케이 에이엑스']),
                    ('samsung_sds', '삼성 SDS', ARRAY['samsung sds', 'samsungsds', '삼성 sds', '삼성sds']),
                    ('lg_cns', 'LG CNS', ARRAY['lg cns', 'lgcns', '엘지 cns', '엘지씨엔에스']),
                    ('hyundai_autoever', '현대 오토에버', ARRAY['hyundai autoever', 'hyundai_autoever', '현대 오토에버', '현대오토에버']),
                    ('posco_dx', '포스코 DX', ARRAY['posco dx', 'posco_dx', '포스코 dx', '포스코dx'])
            ),
            matched_articles AS (
                SELECT
                    tp.id AS peer_id,
                    tp.name AS peer_name,
                    ra.title,
                    ra.content,
                    ra.metadata::text AS metadata_text,
                    COALESCE(ra.published_at, ra.collected_at, ra.created_at) AS article_at,
                    ROW_NUMBER() OVER (
                        PARTITION BY tp.id
                        ORDER BY
                            CASE
                                WHEN COALESCE(ra.content, '') ILIKE '%SWOT 분석%' THEN 0
                                ELSE 1
                            END,
                            CASE
                                WHEN COALESCE(ra.content, '') LIKE '%입사제안 받기%' THEN 1
                                ELSE 0
                            END,
                            COALESCE(ra.published_at, ra.collected_at, ra.created_at) DESC NULLS LAST,
                            ra.id DESC
                    ) AS row_rank
                FROM raw_articles ra
                JOIN target_peers tp
                  ON EXISTS (
                      SELECT 1
                      FROM unnest(tp.aliases) alias
                      WHERE lower(
                          COALESCE(ra.title, '')
                          || ' '
                          || split_part(trim(BOTH ' "' FROM COALESCE(ra.content, '')), E'\n', 1)
                      ) LIKE '%' || alias || '%'
                  )
                WHERE ra.source_name = 'catch_company_analysis'
                  AND COALESCE(ra.content, '') ILIKE '%SWOT 분석%'
                  AND COALESCE(ra.content, '') NOT LIKE '%입사제안 받기%'
            )
            SELECT peer_id, peer_name, title, content, metadata_text
            FROM matched_articles
            WHERE row_rank = 1
            """;

    public static final String PEER_LLM_ANALYSIS_SNAPSHOTS_SQL = """
            WITH latest_snapshots AS (
                SELECT DISTINCT ON (peer_id)
                    peer_id,
                    output_payload::text AS output_payload,
                    analysis_trace::text AS analysis_trace
                FROM peer_llm_analysis_snapshots
                WHERE analysis_type = 'peer_swot_comparison'
                  AND status = 'active'
                  AND peer_id IN ('all', 'samsung_sds', 'lg_cns', 'hyundai_autoever', 'posco_dx')
                  AND (
                      (peer_id = 'all' AND scope = 'all' AND comparison_mode = 'overall_competitors_vs_sk_ax')
                      OR
                      (peer_id <> 'all' AND scope = 'company' AND comparison_mode = 'peer_vs_sk_ax')
                  )
                  AND (expires_at IS NULL OR expires_at > NOW())
                ORDER BY
                    peer_id,
                    updated_at DESC NULLS LAST,
                    generated_at DESC,
                    created_at DESC
            )
            SELECT peer_id, output_payload, analysis_trace
            FROM latest_snapshots
            """;

    public static final String LLM_KEYWORD_ROWS_SQL = """
            SELECT DISTINCT ON (peer_id)
                peer_id,
                output_payload::text AS output_payload,
                confidence
            FROM peer_llm_analysis_snapshots
            WHERE analysis_type = 'peer_overview_keywords'
              AND scope = 'company'
              AND comparison_mode = 'quarterly_keyword_selection'
              AND status = 'active'
              AND peer_id IN ('sk_ax', 'samsung_sds', 'lg_cns', 'hyundai_autoever', 'posco_dx')
              AND (expires_at IS NULL OR expires_at > NOW())
            ORDER BY
                peer_id,
                updated_at DESC NULLS LAST,
                generated_at DESC,
                created_at DESC
            """;

    private PeerOverviewTableQueries() {
    }
}
