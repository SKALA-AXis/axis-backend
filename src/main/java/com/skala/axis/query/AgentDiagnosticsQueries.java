package com.skala.axis.query;

public final class AgentDiagnosticsQueries {
    public static final String LIST_MIXER_RESULTS_SQL = """
            SELECT
                id::text AS id,
                source_analysis_id,
                title,
                final_one_liner,
                sk_ax_implication,
                confidence::double precision AS confidence,
                array_to_json(input_peer_ids)::text AS peer_ids_json,
                array_to_json(input_card_ids)::text AS source_ids_json,
                created_at::text AS created_at,
                updated_at::text AS updated_at
            FROM mixer_results
            ORDER BY created_at DESC
            LIMIT ? OFFSET ?
            """;

    public static final String LIST_INSIGHT_REPORTS_SQL = """
            SELECT
                id::text AS id,
                source_analysis_id,
                title,
                final_one_liner,
                sk_ax_implication,
                confidence::double precision AS confidence,
                array_to_json(focus_peer_ids)::text AS peer_ids_json,
                array_to_json(source_card_ids)::text AS source_ids_json,
                created_at::text AS created_at,
                updated_at::text AS updated_at
            FROM insight_reports
            ORDER BY created_at DESC
            LIMIT ? OFFSET ?
            """;

    public static final String LIST_GLOBAL_TRENDS_SQL = """
            SELECT
                id::text AS id,
                source_analysis_id,
                title,
                summary AS final_one_liner,
                sk_ax_implication,
                confidence::double precision AS confidence,
                array_to_json(related_peer_ids)::text AS peer_ids_json,
                array_to_json(related_card_ids)::text AS source_ids_json,
                industry,
                region,
                keyword,
                keyword_category,
                trend_date::text AS trend_date,
                created_at::text AS created_at,
                updated_at::text AS updated_at
            FROM global_industry_trends
            ORDER BY trend_date DESC, created_at DESC
            LIMIT ? OFFSET ?
            """;

    public static final String LIST_BRIEFINGS_SQL = """
            SELECT
                id,
                NULL AS source_analysis_id,
                title,
                COALESCE(NULLIF(key_summary, ''), title) AS final_one_liner,
                sk_implication AS sk_ax_implication,
                confidence::double precision AS confidence,
                '[]'::json AS peer_ids_json,
                array_to_json(related_card_ids)::text AS source_ids_json,
                briefing_type,
                status,
                created_at::text AS created_at,
                completed_at::text AS updated_at
            FROM briefing_reports
            ORDER BY created_at DESC
            LIMIT ? OFFSET ?
            """;

    public static final String LIST_BRIEFINGS_FALLBACK_SQL = """
            SELECT
                id,
                NULL AS source_analysis_id,
                title,
                title AS final_one_liner,
                NULL AS sk_ax_implication,
                confidence::double precision AS confidence,
                '[]'::json AS peer_ids_json,
                '[]'::json AS source_ids_json,
                briefing_type,
                status,
                created_at::text AS created_at,
                completed_at::text AS updated_at
            FROM briefing_reports
            ORDER BY created_at DESC
            LIMIT ? OFFSET ?
            """;

    public static final String LIST_INTEGRATED_ISSUES_SQL = """
            SELECT
                id::text AS id,
                issue_key,
                headline AS title,
                one_line_summary AS final_one_liner,
                main_company,
                event_type,
                confidence::double precision AS confidence,
                is_valid,
                status,
                array_to_json(mentioned_peer_companies)::text AS peer_ids_json,
                array_to_json(source_ids)::text AS source_ids_json,
                created_at::text AS created_at,
                updated_at::text AS updated_at
            FROM integrated_issues
            ORDER BY created_at DESC
            LIMIT ? OFFSET ?
            """;

    public static String countSql(String tableName) {
        return "SELECT COUNT(*) FROM " + tableName;
    }

    public static String detailSql(String tableName, boolean hasSourceAnalysisId) {
        if (hasSourceAnalysisId) {
            return "SELECT to_jsonb(t)::text AS row_json FROM " + tableName
                    + " t WHERE t.id::text = ? OR t.source_analysis_id = ? LIMIT 1";
        }
        return "SELECT to_jsonb(t)::text AS row_json FROM " + tableName
                + " t WHERE t.id::text = ? LIMIT 1";
    }

    private AgentDiagnosticsQueries() {
    }
}
