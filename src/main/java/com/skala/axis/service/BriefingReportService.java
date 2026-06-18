/*
 * 작성일: 2026-06-12
 * 작성자: 박진
 * 변경이력:
 *   2026-06-12 박진 — 생성된 브리핑 워크플로 노출 및 목업 삭제·챗봇 고도화
 *   2026-06-17 최종민 — 순수 read JDBC 서비스에 @Transactional(readOnly=true) 명시
 */
package com.skala.axis.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.skala.axis.query.BriefingReportQueries.COMPLETED_ROW_FOR_ANCHOR_SQL;
import static com.skala.axis.query.BriefingReportQueries.DETAIL_BY_ID_SQL;
import static com.skala.axis.query.BriefingReportQueries.LATEST_COMPLETED_ROWS_SQL;
import static com.skala.axis.query.BriefingReportQueries.LATEST_COMPLETED_ROW_SQL;
import static com.skala.axis.query.BriefingReportQueries.STATUS_BY_ID_SQL;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BriefingReportService {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public Optional<Map<String, Object>> findOverview(LocalDate anchorDate) {
        List<BriefingRow> rows = new ArrayList<>(findRowsForOverview(anchorDate));
        if (rows.isEmpty()) {
            return Optional.empty();
        }

        BriefingRow daily = firstByType(rows, "daily").orElse(rows.get(0));
        Optional<BriefingRow> weekly = firstByType(rows, "weekly");
        Optional<BriefingRow> monthly = firstByType(rows, "monthly");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dailySnapshot", snapshot(daily));
        result.put("weeklySnapshot", weekly.map(this::snapshot).orElseGet(() -> unavailableSnapshot("주간")));
        result.put("monthlySnapshot", monthly.map(this::snapshot).orElseGet(() -> unavailableSnapshot("월간")));
        result.put("evidenceSources", evidenceSources(rows));
        result.put("history", rows.stream().map(this::historyItem).toList());
        result.put("result_kind", "saved_briefings");
        result.put("anchor_date", anchorDate.toString());
        return Optional.of(result);
    }

    public Optional<Map<String, Object>> findPayloadForPeriod(String briefingType, LocalDate anchorDate) {
        return findCompletedRowForAnchor(briefingType, anchorDate)
                .or(() -> findLatestCompletedRow(briefingType, anchorDate))
                .map(this::payloadWithMetadata);
    }

    public Optional<Map<String, Object>> findLatestPayload(String briefingType, LocalDate anchorDate) {
        return findLatestCompletedRow(briefingType, anchorDate).map(this::payloadWithMetadata);
    }

    public Optional<Map<String, Object>> findById(String briefingId) {
        try {
            List<BriefingRow> rows = jdbcTemplate.query(
                    DETAIL_BY_ID_SQL,
                    (rs, rowNum) -> mapRow(rs),
                    briefingId
            );
            return rows.stream().findFirst().map(this::payloadWithMetadata);
        } catch (DataAccessException e) {
            log.warn("Briefing report detail query failed | id={} error={}", briefingId, rootMessage(e));
            return Optional.empty();
        }
    }

    public Optional<Map<String, Object>> findStatus(String briefingId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    STATUS_BY_ID_SQL,
                    briefingId
            );
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            Map<String, Object> row = rows.get(0);
            return Optional.of(Map.of(
                    "briefing_id", String.valueOf(row.get("id")),
                    "status", String.valueOf(row.get("status")),
                    "progress", row.get("progress"),
                    "error_message", row.get("error_message") == null ? "" : String.valueOf(row.get("error_message")),
                    "created_at", row.get("created_at") == null ? "" : String.valueOf(row.get("created_at")),
                    "completed_at", row.get("completed_at") == null ? "" : String.valueOf(row.get("completed_at")),
                    "result_kind", "saved_briefing_status"
            ));
        } catch (DataAccessException e) {
            log.warn("Briefing report status query failed | id={} error={}", briefingId, rootMessage(e));
            return Optional.empty();
        }
    }

    private List<BriefingRow> findLatestCompletedRows(LocalDate anchorDate, int limit) {
        try {
            return jdbcTemplate.query(
                    LATEST_COMPLETED_ROWS_SQL,
                    (rs, rowNum) -> mapRow(rs),
                    anchorDate.toString(),
                    Math.max(1, Math.min(limit, 50))
            );
        } catch (DataAccessException e) {
            log.warn("Briefing reports overview query failed | anchor={} error={}", anchorDate, rootMessage(e));
            return List.of();
        }
    }

    private List<BriefingRow> findRowsForOverview(LocalDate anchorDate) {
        List<BriefingRow> rows = new ArrayList<>();
        for (String type : List.of("daily", "weekly", "monthly")) {
            findCompletedRowForAnchor(type, anchorDate)
                    .or(() -> findLatestCompletedRow(type, anchorDate))
                    .ifPresent(rows::add);
        }

        for (BriefingRow row : findLatestCompletedRows(anchorDate, 20)) {
            if (rows.stream().noneMatch(existing -> existing.id().equals(row.id()))) {
                rows.add(row);
            }
            if (rows.size() >= 20) {
                break;
            }
        }
        return rows;
    }

    private Optional<BriefingRow> findCompletedRowForAnchor(String briefingType, LocalDate anchorDate) {
        try {
            List<BriefingRow> rows = jdbcTemplate.query(
                    COMPLETED_ROW_FOR_ANCHOR_SQL,
                    (rs, rowNum) -> mapRow(rs),
                    briefingType,
                    anchorDate.toString()
            );
            return rows.stream().findFirst();
        } catch (DataAccessException e) {
            log.warn("Briefing report period query failed | type={} anchor={} error={}",
                    briefingType, anchorDate, rootMessage(e));
            return Optional.empty();
        }
    }

    private Optional<BriefingRow> findLatestCompletedRow(String briefingType, LocalDate anchorDate) {
        try {
            List<BriefingRow> rows = jdbcTemplate.query(
                    LATEST_COMPLETED_ROW_SQL,
                    (rs, rowNum) -> mapRow(rs),
                    briefingType,
                    anchorDate.toString()
            );
            return rows.stream().findFirst();
        } catch (DataAccessException e) {
            log.warn("Briefing report latest query failed | type={} anchor={} error={}",
                    briefingType, anchorDate, rootMessage(e));
            return Optional.empty();
        }
    }

    private BriefingRow mapRow(ResultSet rs) throws SQLException {
        return new BriefingRow(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("briefing_type"),
                rs.getString("date_from"),
                rs.getString("date_to"),
                rs.getString("report_date"),
                rs.getString("period_label"),
                rs.getString("status"),
                rs.getObject("progress"),
                rs.getString("key_summary"),
                rs.getString("sk_implication"),
                rs.getInt("primary_count"),
                parseMap(rs.getString("payload_json")),
                parseMap(rs.getString("provenance_json")),
                rs.getString("created_at"),
                rs.getString("completed_at")
        );
    }

    private Map<String, Object> snapshot(BriefingRow row) {
        Map<String, Object> payloadSnapshot = snapshotFromPayload(row);
        if (!payloadSnapshot.isEmpty()) {
            return payloadSnapshot;
        }
        return Map.of(
                "title", firstText(row.title(), row.periodLabel(), "저장된 브리핑"),
                "summary", firstText(row.keySummary(), row.skImplication(), stringValue(row.payload().get("executive_summary"))),
                "sections", sectionsFromPayload(row.payload(), row)
        );
    }

    private Map<String, Object> snapshotFromPayload(BriefingRow row) {
        String key = snapshotKey(row.briefingType());
        Map<String, Object> direct = mapValue(row.payload().get(key));
        if (!direct.isEmpty()) {
            return normalizeSnapshot(direct);
        }

        Map<String, Object> frontend = mapValue(row.payload().get("frontend_briefings"));
        Map<String, Object> nested = mapValue(frontend.get(key));
        return nested.isEmpty() ? Map.of() : normalizeSnapshot(nested);
    }

    private String snapshotKey(String briefingType) {
        return switch (briefingType) {
            case "weekly" -> "weeklySnapshot";
            case "monthly" -> "monthlySnapshot";
            default -> "dailySnapshot";
        };
    }

    private Map<String, Object> normalizeSnapshot(Map<String, Object> source) {
        return Map.of(
                "title", firstText(source.get("title"), source.get("headline")),
                "summary", firstText(source.get("summary"), source.get("executive_summary")),
                "sections", normalizedSections(source.get("sections"))
        );
    }

    private Map<String, Object> unavailableSnapshot(String label) {
        return Map.of(
                "title", "저장된 " + label + " 브리핑이 없습니다",
                "summary", label + " 브리핑 생성 결과가 저장되면 이 영역에 표시됩니다.",
                "sections", List.of()
        );
    }

    private List<Map<String, Object>> sectionsFromPayload(Map<String, Object> payload, BriefingRow row) {
        List<Map<String, Object>> direct = normalizedSections(payload.get("sections"));
        if (!direct.isEmpty()) {
            return direct;
        }

        List<Map<String, Object>> sections = new ArrayList<>();
        addTrendSection(sections, "핵심 변화", payload.get("immediate_trends"));
        addTrendSection(sections, "관찰 신호", payload.get("watch_trends"));
        addEvidenceSection(sections, payload.get("evidence_summary"));
        if (sections.isEmpty() && !firstText(row.keySummary(), row.skImplication()).isBlank()) {
            sections.add(Map.of(
                    "title", "요약",
                    "items", List.of(Map.of(
                            "headline", firstText(row.keySummary(), row.skImplication()),
                            "source", "briefing_reports"
                    ))
            ));
        }
        return sections;
    }

    private void addTrendSection(List<Map<String, Object>> sections, String title, Object value) {
        List<Map<String, Object>> items = listValue(value).stream()
                .map(this::trendItem)
                .filter(item -> !firstText(item.get("headline"), item.get("source")).isBlank())
                .toList();
        if (!items.isEmpty()) {
            sections.add(Map.of("title", title, "items", items));
        }
    }

    private Map<String, Object> trendItem(Object value) {
        Map<String, Object> item = mapValue(value);
        if (item.isEmpty()) {
            String text = stringValue(value);
            return text.isBlank() ? Map.of() : Map.of("headline", text, "source", "briefing_reports");
        }
        return Map.of(
                "headline", firstText(item.get("headline"), item.get("title"), item.get("reason")),
                "source", firstText(item.get("source"), item.get("source_name"), item.get("reason"), "briefing_reports")
        );
    }

    private void addEvidenceSection(List<Map<String, Object>> sections, Object value) {
        List<Map<String, Object>> items = listValue(value).stream()
                .map(item -> stringValue(item))
                .filter(text -> !text.isBlank())
                .limit(4)
                .map(text -> Map.<String, Object>of("headline", text, "source", "briefing evidence"))
                .toList();
        if (!items.isEmpty()) {
            sections.add(Map.of("title", "판단 근거", "items", items));
        }
    }

    private List<Map<String, Object>> normalizedSections(Object value) {
        return listValue(value).stream()
                .map(item -> {
                    Map<String, Object> section = mapValue(item);
                    if (section.isEmpty()) {
                        return Map.<String, Object>of();
                    }
                    return Map.of(
                            "title", firstText(section.get("title"), section.get("label")),
                            "items", normalizedSectionItems(section.get("items"))
                    );
                })
                .filter(section -> !firstText(section.get("title")).isBlank()
                        || !listValue(section.get("items")).isEmpty())
                .toList();
    }

    private List<Map<String, Object>> normalizedSectionItems(Object value) {
        return listValue(value).stream()
                .map(item -> {
                    Map<String, Object> source = mapValue(item);
                    if (source.isEmpty()) {
                        String text = stringValue(item);
                        return text.isBlank()
                                ? Map.<String, Object>of()
                                : Map.<String, Object>of("headline", text, "source", "briefing_reports");
                    }
                    return Map.<String, Object>of(
                            "headline", firstText(source.get("headline"), source.get("title"), source.get("description"), source.get("reason")),
                            "source", firstText(source.get("source"), source.get("source_name"), source.get("reason"), "briefing_reports")
                    );
                })
                .filter(item -> !firstText(item.get("headline"), item.get("source")).isBlank())
                .toList();
    }

    private List<String> evidenceSources(List<BriefingRow> rows) {
        List<String> values = new ArrayList<>();
        for (BriefingRow row : rows) {
            for (Object item : listValue(row.payload().get("evidence_summary"))) {
                String text = stringValue(item);
                if (!text.isBlank() && !values.contains(text)) {
                    values.add(text);
                }
                if (values.size() >= 6) {
                    return values;
                }
            }
        }
        if (values.isEmpty()) {
            values.add("briefing_reports");
        }
        return values;
    }

    private Map<String, Object> historyItem(BriefingRow row) {
        return Map.of(
                "id", row.id(),
                "date", firstText(row.reportDate(), row.dateTo(), row.createdAt()),
                "title", firstText(row.title(), row.periodLabel(), "저장된 브리핑"),
                "status", "delivered",
                "summary", firstText(row.keySummary(), row.skImplication(), stringValue(row.payload().get("executive_summary"))),
                "primaryCount", row.primaryCount(),
                "watchCount", listValue(row.payload().get("watch_trends")).size(),
                "evidence", evidenceSources(List.of(row))
        );
    }

    private Map<String, Object> payloadWithMetadata(BriefingRow row) {
        Map<String, Object> payload = new LinkedHashMap<>(row.payload());
        payload.putIfAbsent("id", row.id());
        payload.putIfAbsent("title", row.title());
        payload.putIfAbsent("briefing_type", row.briefingType());
        payload.putIfAbsent("date_from", row.dateFrom());
        payload.putIfAbsent("date_to", row.dateTo());
        payload.putIfAbsent("report_date", row.reportDate());
        payload.putIfAbsent("period_label", row.periodLabel());
        payload.putIfAbsent("status", row.status());
        payload.putIfAbsent("key_summary", row.keySummary());
        payload.put("result_kind", "saved_briefing");
        payload.put("created_at", row.createdAt());
        payload.put("completed_at", row.completedAt());

        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.putAll(row.provenance());
        provenance.putAll(mapValue(payload.get("provenance")));
        provenance.put("cache_lookup", "backend_briefing_reports");
        provenance.put("saved_report_id", row.id());
        payload.put("provenance", provenance);
        return payload;
    }

    private Optional<BriefingRow> firstByType(List<BriefingRow> rows, String type) {
        return rows.stream().filter(row -> type.equals(row.briefingType())).findFirst();
    }

    private Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((key, item) -> out.put(String.valueOf(key), item));
            return out;
        }
        return Map.of();
    }

    private List<Object> listValue(Object value) {
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return List.of();
    }

    private static String firstText(Object... values) {
        for (Object value : values) {
            String text = stringValue(value);
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).replaceAll("\\s+", " ").trim();
    }

    private static String rootMessage(DataAccessException e) {
        Throwable cause = e.getMostSpecificCause();
        return cause == null ? e.getMessage() : cause.getMessage();
    }

    private record BriefingRow(
            String id,
            String title,
            String briefingType,
            String dateFrom,
            String dateTo,
            String reportDate,
            String periodLabel,
            String status,
            Object progress,
            String keySummary,
            String skImplication,
            int primaryCount,
            Map<String, Object> payload,
            Map<String, Object> provenance,
            String createdAt,
            String completedAt
    ) {
    }
}
