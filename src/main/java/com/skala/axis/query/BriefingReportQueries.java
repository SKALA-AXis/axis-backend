package com.skala.axis.query;

public final class BriefingReportQueries {
    private static final String BRIEFING_ROW_COLUMNS = """
            id,
            title,
            briefing_type,
            date_from::text AS date_from,
            date_to::text AS date_to,
            COALESCE(report_date, date_to)::text AS report_date,
            period_label,
            status,
            progress::double precision AS progress,
            key_summary,
            sk_implication,
            cardinality(COALESCE(related_card_ids, '{}'::text[])) AS primary_count,
            payload::text AS payload_json,
            COALESCE(provenance, '{}'::jsonb)::text AS provenance_json,
            created_at::text AS created_at,
            completed_at::text AS completed_at
            """;

    public static final String DETAIL_BY_ID_SQL = "SELECT " + BRIEFING_ROW_COLUMNS + """
              FROM briefing_reports
             WHERE id = ?
             LIMIT 1
            """;

    public static final String STATUS_BY_ID_SQL = """
            SELECT id,
                   status,
                   progress::double precision AS progress,
                   error_message,
                   created_at::text AS created_at,
                   completed_at::text AS completed_at
              FROM briefing_reports
             WHERE id = ?
             LIMIT 1
            """;

    public static final String LATEST_COMPLETED_ROWS_SQL = "SELECT " + BRIEFING_ROW_COLUMNS + """
              FROM briefing_reports
             WHERE COALESCE(report_date, date_to) <= CAST(? AS date)
               AND status IN ('completed', 'completed_partial')
             ORDER BY COALESCE(report_date, date_to) DESC,
                      completed_at DESC NULLS LAST,
                      created_at DESC
             LIMIT ?
            """;

    public static final String COMPLETED_ROW_FOR_ANCHOR_SQL = "SELECT " + BRIEFING_ROW_COLUMNS + """
              FROM briefing_reports
             WHERE briefing_type = ?
               AND CAST(? AS date) BETWEEN date_from AND date_to
               AND status IN ('completed', 'completed_partial')
             ORDER BY completed_at DESC NULLS LAST,
                      created_at DESC
             LIMIT 1
            """;

    public static final String LATEST_COMPLETED_ROW_SQL = "SELECT " + BRIEFING_ROW_COLUMNS + """
              FROM briefing_reports
             WHERE briefing_type = ?
               AND COALESCE(report_date, date_to) <= CAST(? AS date)
               AND status IN ('completed', 'completed_partial')
             ORDER BY COALESCE(report_date, date_to) DESC,
                      completed_at DESC NULLS LAST,
                      created_at DESC
             LIMIT 1
            """;

    private BriefingReportQueries() {
    }
}
