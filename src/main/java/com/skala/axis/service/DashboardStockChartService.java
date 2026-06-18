package com.skala.axis.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.skala.axis.query.DashboardStockChartQueries.DAILY_RATE_SQL;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardStockChartService {
    private static final int CHART_DAYS = 7;
    private static final DateTimeFormatter STOCK_DATE_FORMATTER = DateTimeFormatter.ofPattern("MM.dd");
    private static final List<String> STOCK_PEER_IDS = List.of(
            "samsung_sds",
            "lg_cns",
            "hyundai_autoever",
            "posco_dx"
    );
    private static final Map<String, String> STOCK_FIELD_BY_PEER_ID = Map.of(
            "samsung_sds", "samsungSds",
            "lg_cns", "lgCns",
            "hyundai_autoever", "hyundaiAutoever",
            "posco_dx", "poscoDx"
    );

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public Map<String, Object> applyDailyRateChart(Map<String, Object> dashboardSummary) {
        StockChartPayload livePayload = loadDailyRateChart(CHART_DAYS);
        if (!livePayload.points().isEmpty()) {
            dashboardSummary.put("stockPoints", livePayload.closePoints());
            dashboardSummary.put("stockRatePoints", livePayload.points());
            dashboardSummary.put("stockSource", livePayload.source());
            return dashboardSummary;
        }

        List<Map<String, Object>> fallbackPoints = buildFallbackRatePoints(dashboardSummary.get("stockPoints"));
        if (!fallbackPoints.isEmpty()) {
            dashboardSummary.put("stockRatePoints", fallbackPoints);
            dashboardSummary.put("stockSource", buildSourceMetadata("preloaded stockPoints", null, null, true));
        }
        return dashboardSummary;
    }

    private StockChartPayload loadDailyRateChart(int chartDays) {
        try {
            List<DailyRateRow> rows = jdbcTemplate.query(
                    DAILY_RATE_SQL,
                    new MapSqlParameterSource()
                            .addValue("peerIds", STOCK_PEER_IDS)
                            .addValue("historySize", chartDays),
                    this::mapDailyRateRow
            );

            if (rows.isEmpty()) {
                return StockChartPayload.empty();
            }

            Map<LocalDate, Map<String, Object>> pointsByDate = new LinkedHashMap<>();
            Map<LocalDate, Map<String, Object>> closePointsByDate = new LinkedHashMap<>();
            LinkedHashSet<String> sourceNames = new LinkedHashSet<>();
            LinkedHashSet<String> exchanges = new LinkedHashSet<>();
            LinkedHashSet<String> currencies = new LinkedHashSet<>();

            for (DailyRateRow row : rows) {
                String fieldKey = STOCK_FIELD_BY_PEER_ID.get(row.peerId());
                if (fieldKey == null) {
                    continue;
                }

                Map<String, Object> point = pointsByDate.computeIfAbsent(
                        row.tradeDate(),
                        tradeDate -> emptyRatePoint(STOCK_DATE_FORMATTER.format(tradeDate))
                );
                point.put(fieldKey, toScaledDouble(row.dayChangePct()));

                Map<String, Object> closePoint = closePointsByDate.computeIfAbsent(
                        row.tradeDate(),
                        tradeDate -> emptyClosePoint(STOCK_DATE_FORMATTER.format(tradeDate))
                );
                closePoint.put(fieldKey, toInteger(row.close()));

                addIfPresent(sourceNames, row.sourceName());
                addIfPresent(exchanges, row.exchange());
                addIfPresent(currencies, row.currency());
            }

            List<Map<String, Object>> points = pointsByDate.values().stream()
                    .filter(this::containsAnyRateValue)
                    .toList();

            if (points.isEmpty()) {
                return StockChartPayload.empty();
            }

            return new StockChartPayload(
                    closePointsByDate.values().stream()
                            .filter(this::containsAnyCloseValue)
                            .toList(),
                    points,
                    buildSourceMetadata(singleOrSummary(sourceNames), singleOrSummary(exchanges), singleOrSummary(currencies), false)
            );
        } catch (DataAccessException ignored) {
            return StockChartPayload.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildFallbackRatePoints(Object rawStockPoints) {
        if (!(rawStockPoints instanceof List<?> items) || items.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> stockPoints = items.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .toList();

        if (stockPoints.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> ratePoints = new ArrayList<>();
        for (int index = 0; index < stockPoints.size(); index++) {
            Map<String, Object> current = stockPoints.get(index);
            Map<String, Object> point = emptyRatePoint(String.valueOf(current.get("date")));
            if (index == 0) {
                point.put("samsungSds", 0.0);
                point.put("lgCns", 0.0);
                point.put("hyundaiAutoever", 0.0);
                point.put("poscoDx", 0.0);
            } else {
                Map<String, Object> previous = stockPoints.get(index - 1);
                point.put("samsungSds", toRate(previous.get("samsungSds"), current.get("samsungSds")));
                point.put("lgCns", toRate(previous.get("lgCns"), current.get("lgCns")));
                point.put("hyundaiAutoever", toRate(previous.get("hyundaiAutoever"), current.get("hyundaiAutoever")));
                point.put("poscoDx", toRate(previous.get("poscoDx"), current.get("poscoDx")));
            }
            if (containsAnyRateValue(point)) {
                ratePoints.add(point);
            }
        }

        return ratePoints;
    }

    private DailyRateRow mapDailyRateRow(ResultSet rs, int rowNum) throws SQLException {
        return new DailyRateRow(
                rs.getString("peer_id"),
                rs.getObject("trade_date", LocalDate.class),
                rs.getBigDecimal("close"),
                rs.getBigDecimal("day_change_pct"),
                rs.getString("source_name"),
                rs.getString("exchange"),
                rs.getString("currency")
        );
    }

    private Map<String, Object> emptyRatePoint(String date) {
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("date", date);
        point.put("samsungSds", null);
        point.put("lgCns", null);
        point.put("hyundaiAutoever", null);
        point.put("poscoDx", null);
        return point;
    }

    private Map<String, Object> emptyClosePoint(String date) {
        return emptyRatePoint(date);
    }

    private Map<String, Object> buildSourceMetadata(String sourceName, String exchange, String currency, boolean isMock) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("basis", "day_over_day_pct");
        source.put("windowDays", CHART_DAYS);
        source.put("sourceName", sourceName);
        source.put("exchange", exchange);
        source.put("currency", currency);
        source.put("isMock", isMock);
        source.put("label", buildSourceLabel(sourceName, exchange, currency, isMock));
        return source;
    }

    private String buildSourceLabel(String sourceName, String exchange, String currency, boolean isMock) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        tokens.add("전일 대비 증감률");
        addIfPresent(tokens, exchange);
        addIfPresent(tokens, currency);
        addIfPresent(tokens, sourceName);
        if (isMock) {
            tokens.add("fallback");
        }
        return String.join(" · ", tokens);
    }

    private boolean containsAnyRateValue(Map<String, Object> point) {
        return point.get("samsungSds") != null
                || point.get("lgCns") != null
                || point.get("hyundaiAutoever") != null
                || point.get("poscoDx") != null;
    }

    private boolean containsAnyCloseValue(Map<String, Object> point) {
        return containsAnyRateValue(point);
    }

    private Double toRate(Object previousValue, Object currentValue) {
        BigDecimal previous = toBigDecimal(previousValue);
        BigDecimal current = toBigDecimal(currentValue);
        if (previous == null || current == null || BigDecimal.ZERO.compareTo(previous) == 0) {
            return null;
        }

        BigDecimal rate = current.subtract(previous)
                .divide(previous, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
        return rate.doubleValue();
    }

    private Double toScaledDouble(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private Integer toInteger(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.intValue();
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return new BigDecimal(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private void addIfPresent(Collection<String> target, String value) {
        if (value != null && !value.isBlank()) {
            target.add(value);
        }
    }

    private String singleOrSummary(Collection<String> values) {
        if (values.isEmpty()) {
            return null;
        }
        if (values.size() == 1) {
            return values.iterator().next();
        }
        return String.join(", ", values.stream().filter(Objects::nonNull).toList());
    }

    private record DailyRateRow(
            String peerId,
            LocalDate tradeDate,
            BigDecimal close,
            BigDecimal dayChangePct,
            String sourceName,
            String exchange,
            String currency
    ) {}

    private record StockChartPayload(
            List<Map<String, Object>> closePoints,
            List<Map<String, Object>> points,
            Map<String, Object> source
    ) {
        private static StockChartPayload empty() {
            return new StockChartPayload(List.of(), List.of(), Map.of());
        }
    }
}
