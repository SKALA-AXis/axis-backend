package com.skala.axis.query;

public final class DashboardStockChartQueries {
    public static final String DAILY_RATE_SQL = """
            WITH latest_per_day AS (
                SELECT
                    COALESCE(peer_company_id, peer_id) AS peer_id,
                    trade_date,
                    close,
                    source_name,
                    exchange,
                    currency,
                    collected_at,
                    id,
                    ROW_NUMBER() OVER (
                        PARTITION BY COALESCE(peer_company_id, peer_id), trade_date
                        ORDER BY collected_at DESC NULLS LAST, id DESC
                    ) AS rn
                FROM market_price_ohlcv
                WHERE COALESCE(peer_company_id, peer_id) IN (:peerIds)
                  AND close IS NOT NULL
            ),
            deduped AS (
                SELECT
                    peer_id,
                    trade_date,
                    close,
                    source_name,
                    exchange,
                    currency
                FROM latest_per_day
                WHERE rn = 1
            ),
            recent_dates AS (
                SELECT trade_date
                FROM deduped
                GROUP BY trade_date
                ORDER BY trade_date DESC
                LIMIT :historySize
            ),
            windowed AS (
                SELECT
                    d.peer_id,
                    d.trade_date,
                    d.close,
                    d.source_name,
                    d.exchange,
                    d.currency,
                    LAG(d.close) OVER (
                        PARTITION BY d.peer_id
                        ORDER BY d.trade_date
                    ) AS prev_close
                FROM deduped d
                WHERE d.trade_date IN (SELECT trade_date FROM recent_dates)
            )
            SELECT
                peer_id,
                trade_date,
                close,
                prev_close,
                CASE
                    WHEN prev_close IS NULL OR prev_close = 0 THEN 0
                    ELSE ROUND(((close - prev_close) / NULLIF(prev_close, 0)) * 100, 2)
                END AS day_change_pct,
                source_name,
                exchange,
                currency
            FROM windowed
            ORDER BY trade_date ASC, peer_id ASC
            """;

    private DashboardStockChartQueries() {
    }
}
