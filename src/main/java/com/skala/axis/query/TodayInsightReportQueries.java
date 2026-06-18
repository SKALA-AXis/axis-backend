package com.skala.axis.query;

public final class TodayInsightReportQueries {
    public static final String LATEST_ON_OR_BEFORE_SQL = """
            SELECT report_date::text AS report_date,
                   output_payload::text AS output_payload,
                   created_at
              FROM today_insight_reports
             WHERE report_date <= CAST(? AS date)
               AND status = 'active'
             ORDER BY report_date DESC, created_at DESC
             LIMIT ?
            """;

    private TodayInsightReportQueries() {
    }
}
