package com.skala.axis.tools;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PeerFinancialMetricsDebugMain {
    private static final List<String> DISPLAY_PEERS = List.of(
            "sk_ax",
            "samsung_sds",
            "lg_cns",
            "hyundai_autoever",
            "posco_dx"
    );

    private PeerFinancialMetricsDebugMain() {
    }

    public static void main(String[] args) throws Exception {
        String requestedPeriod = parsePeriodArg(args);
        String url = firstNonBlank(
                System.getenv("SPRING_DATASOURCE_URL"),
                System.getenv("DATABASE_URL"),
                "jdbc:postgresql://localhost:5432/axis"
        );
        String username = firstNonBlank(
                System.getenv("SPRING_DATASOURCE_USERNAME"),
                System.getenv("DB_USERNAME"),
                "axuser"
        );
        String password = firstNonBlank(
                System.getenv("SPRING_DATASOURCE_PASSWORD"),
                System.getenv("DB_PASSWORD"),
                "axpass"
        );

        System.out.println("== PeerFinancialMetricsDebug ==");
        System.out.println("url      : " + url);
        System.out.println("username : " + username);
        System.out.println("period   : " + (requestedPeriod == null ? "(auto common period)" : requestedPeriod));
        System.out.println();

        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            printLabelCoverage(connection);
            String resolvedPeriod = requestedPeriod == null ? resolveCommonPeriod(connection) : requestedPeriod;
            System.out.println();
            System.out.println("Resolved period: " + (resolvedPeriod == null ? "(none)" : resolvedPeriod));
            System.out.println();
            printPeriodRows(connection, resolvedPeriod);
        }
    }

    private static void printLabelCoverage(Connection connection) throws SQLException {
        String sql = """
                SELECT
                    peer_id,
                    period,
                    COALESCE(NULLIF(metric_label, ''), metric_name) AS metric_key,
                    COUNT(*) AS row_count,
                    MAX(updated_at) AS latest_updated_at
                FROM raw_article_financial_metrics
                WHERE metric_scope = 'company_total'
                  AND source_type IN ('dart', 'ir', 'securities_report')
                  AND peer_id IN (?, ?, ?, ?, ?)
                GROUP BY peer_id, period, COALESCE(NULLIF(metric_label, ''), metric_name)
                ORDER BY period DESC, peer_id, metric_key
                LIMIT 200
                """;

        System.out.println("== Coverage by peer / period / metric ==");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindDisplayPeers(statement);
            printQuery(statement);
        }
    }

    private static String resolveCommonPeriod(Connection connection) throws SQLException {
        String sql = """
                WITH metric_rows AS (
                    SELECT
                        peer_id,
                        period,
                        CASE
                            WHEN COALESCE(NULLIF(metric_label, ''), metric_name) IN ('매출', '매출액', '총매출', 'revenue_total', 'revenue') THEN 'revenue_total'
                            WHEN COALESCE(NULLIF(metric_label, ''), metric_name) IN ('영업이익', 'operating_profit', 'operating_income') THEN 'operating_profit'
                            WHEN COALESCE(NULLIF(metric_label, ''), metric_name) IN ('영업이익률', 'operating_margin') THEN 'operating_margin'
                            ELSE COALESCE(NULLIF(metric_label, ''), metric_name)
                        END AS metric_name_canonical,
                        COALESCE(value_krwbn, value_numeric::double precision) AS metric_value,
                        ROW_NUMBER() OVER (
                            PARTITION BY peer_id, period,
                                CASE
                                    WHEN COALESCE(NULLIF(metric_label, ''), metric_name) IN ('매출', '매출액', '총매출', 'revenue_total', 'revenue') THEN 'revenue_total'
                                    WHEN COALESCE(NULLIF(metric_label, ''), metric_name) IN ('영업이익', 'operating_profit', 'operating_income') THEN 'operating_profit'
                                    WHEN COALESCE(NULLIF(metric_label, ''), metric_name) IN ('영업이익률', 'operating_margin') THEN 'operating_margin'
                                    ELSE COALESCE(NULLIF(metric_label, ''), metric_name)
                                END
                            ORDER BY
                                CASE
                                    WHEN peer_id = 'sk_ax' AND source_type = 'ir' THEN 1
                                    WHEN peer_id = 'sk_ax' AND source_type = 'securities_report' THEN 2
                                    WHEN source_type = 'dart' THEN 1
                                    WHEN source_type = 'ir' THEN 2
                                    WHEN source_type = 'securities_report' THEN 3
                                    ELSE 99
                                END,
                                confidence DESC NULLS LAST,
                                updated_at DESC NULLS LAST,
                                id DESC
                        ) AS row_rank
                    FROM raw_article_financial_metrics
                    WHERE metric_scope = 'company_total'
                      AND source_type IN ('dart', 'ir', 'securities_report')
                      AND peer_id IN (?, ?, ?, ?, ?)
                      AND period IS NOT NULL
                ),
                latest_metric_rows AS (
                    SELECT *
                    FROM metric_rows
                    WHERE row_rank = 1
                ),
                period_coverage AS (
                    SELECT
                        peer_id,
                        period,
                        MAX(CASE WHEN metric_name_canonical = 'revenue_total' THEN metric_value END) AS revenue_total,
                        MAX(CASE WHEN metric_name_canonical = 'operating_profit' THEN metric_value END) AS operating_profit
                    FROM latest_metric_rows
                    GROUP BY peer_id, period
                )
                SELECT period
                FROM period_coverage
                WHERE revenue_total IS NOT NULL
                  AND operating_profit IS NOT NULL
                GROUP BY period
                HAVING COUNT(DISTINCT peer_id) = ?
                ORDER BY
                    MAX(COALESCE(NULLIF(SUBSTRING(period FROM '^([0-9]{4})'), '')::int, 0)) DESC,
                    MAX(COALESCE(NULLIF(SUBSTRING(period FROM 'Q([1-4])$'), '')::int, 0)) DESC,
                    period DESC
                LIMIT 1
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindDisplayPeers(statement);
            statement.setInt(DISPLAY_PEERS.size() + 1, DISPLAY_PEERS.size());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return resultSet.getString("period");
            }
        }
    }

    private static void printPeriodRows(Connection connection, String period) throws SQLException {
        if (period == null || period.isBlank()) {
            System.out.println("No common period found for revenue + operating profit across all display peers.");
            return;
        }

        String sql = """
                WITH metric_rows AS (
                    SELECT
                        peer_id,
                        period,
                        COALESCE(NULLIF(metric_label, ''), metric_name) AS metric_key,
                        value_krwbn,
                        value_numeric,
                        currency,
                        unit,
                        confidence,
                        raw_article_id,
                        ROW_NUMBER() OVER (
                            PARTITION BY peer_id, period, COALESCE(NULLIF(metric_label, ''), metric_name)
                            ORDER BY
                                CASE
                                    WHEN COALESCE(NULLIF(metric_label, ''), metric_name) IN ('영업이익률', 'operating_margin') THEN
                                        CASE
                                            WHEN peer_id = 'sk_ax' AND source_type = 'ir' THEN 1
                                            WHEN source_type = 'dart' THEN 1
                                            WHEN source_type = 'ir' THEN 2
                                            WHEN source_type = 'securities_report' THEN 3
                                            ELSE 99
                                        END
                                    ELSE
                                        CASE
                                            WHEN peer_id = 'sk_ax' AND source_type = 'ir' THEN 1
                                            WHEN peer_id = 'sk_ax' AND source_type = 'securities_report' THEN 2
                                            WHEN source_type = 'dart' THEN 1
                                            WHEN source_type = 'ir' THEN 2
                                            WHEN source_type = 'securities_report' THEN 3
                                            ELSE 99
                                        END
                                END,
                                confidence DESC NULLS LAST,
                                updated_at DESC NULLS LAST,
                                id DESC
                        ) AS row_rank
                    FROM raw_article_financial_metrics
                    WHERE metric_scope = 'company_total'
                      AND source_type IN ('dart', 'ir', 'securities_report')
                      AND period = ?
                      AND peer_id IN (?, ?, ?, ?, ?)
                      AND COALESCE(NULLIF(metric_label, ''), metric_name) IN (
                          '매출', '매출액', '총매출', 'revenue_total', 'revenue',
                          '영업이익', 'operating_profit', 'operating_income',
                          '영업이익률', 'operating_margin',
                          'PER', 'PBR', 'EPS', 'eps', 'per', 'pbr'
                      )
                )
                SELECT
                    peer_id,
                    period,
                    metric_key,
                    value_krwbn,
                    value_numeric,
                    currency,
                    unit,
                    confidence,
                    raw_article_id
                FROM metric_rows
                WHERE row_rank = 1
                ORDER BY peer_id, metric_key
                """;

        System.out.println("== Latest metric rows for selected period ==");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, period);
            bindDisplayPeers(statement, 2);
            printQuery(statement);
        }
    }

    private static void printQuery(PreparedStatement statement) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery()) {
            ResultSetMetaData metaData = resultSet.getMetaData();
            int columnCount = metaData.getColumnCount();
            List<Map<String, Object>> rows = new ArrayList<>();

            while (resultSet.next()) {
                rows.add(readRow(resultSet, metaData, columnCount));
            }

            if (rows.isEmpty()) {
                System.out.println("(no rows)");
                return;
            }

            for (Map<String, Object> row : rows) {
                System.out.println(row);
            }
        }
    }

    private static Map<String, Object> readRow(ResultSet resultSet, ResultSetMetaData metaData, int columnCount)
            throws SQLException {
        java.util.LinkedHashMap<String, Object> row = new java.util.LinkedHashMap<>();
        for (int index = 1; index <= columnCount; index++) {
            row.put(metaData.getColumnLabel(index), resultSet.getObject(index));
        }
        return row;
    }

    private static void bindDisplayPeers(PreparedStatement statement) throws SQLException {
        bindDisplayPeers(statement, 1);
    }

    private static void bindDisplayPeers(PreparedStatement statement, int startIndex) throws SQLException {
        for (int offset = 0; offset < DISPLAY_PEERS.size(); offset++) {
            statement.setString(startIndex + offset, DISPLAY_PEERS.get(offset));
        }
    }

    private static String parsePeriodArg(String[] args) {
        for (String arg : args) {
            if (arg != null && arg.toLowerCase(Locale.ROOT).startsWith("--period=")) {
                String value = arg.substring("--period=".length()).trim();
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
