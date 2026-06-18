package com.skala.axis.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.skala.axis.query.PeerOverviewFinancialQueries.PEER_FINANCIAL_ROWS_SQL;
import static com.skala.axis.query.PeerOverviewFinancialQueries.RAW_FINANCIAL_ROWS_SQL;
import static com.skala.axis.query.PeerOverviewFinancialQueries.RESOLVE_PEER_FINANCIALS_COMMON_PERIOD_SQL;
import static com.skala.axis.query.PeerOverviewFinancialQueries.RESOLVE_RAW_FINANCIAL_COMMON_PERIOD_SQL;
import static com.skala.axis.query.PeerOverviewPositioningQueries.LATEST_POSITIONING_POINTS_SQL;
import static com.skala.axis.query.PeerOverviewPositioningQueries.POSITIONING_POINTS_SQL;
import static com.skala.axis.query.PeerOverviewPositioningQueries.RESOLVE_COMMON_PERIOD_SQL;
import static com.skala.axis.query.PeerOverviewTableQueries.LATEST_DATA_VERSION_SQL;
import static com.skala.axis.query.PeerOverviewTableQueries.LLM_KEYWORD_ROWS_SQL;
import static com.skala.axis.query.PeerOverviewTableQueries.PEER_LLM_ANALYSIS_SNAPSHOTS_SQL;
import static com.skala.axis.query.PeerOverviewTableQueries.SWOT_INSIGHTS_SQL;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PeerOverviewTableService {
    private static final List<String> FINANCIAL_PEER_IDS = List.of(
            "sk_ax",
            "samsung_sds",
            "lg_cns",
            "hyundai_autoever",
            "posco_dx"
    );

    private static final List<DisplayPeer> DISPLAY_PEERS = List.of(
            new DisplayPeer(0, "sk_ax", "SK AX"),
            new DisplayPeer(1, "samsung_sds", "삼성 SDS"),
            new DisplayPeer(2, "lg_cns", "LG CNS"),
            new DisplayPeer(3, "hyundai_autoever", "현대 오토에버"),
            new DisplayPeer(4, "posco_dx", "포스코 DX")
    );
    private static final Pattern INTERNAL_EVIDENCE_MARKER = Pattern.compile(
            "\\b(?:raw_article_business_signals|raw_articles|peer_llm_analysis_snapshots|peer_companies|"
                    + "business_area|signal_type|raw_article_id|source_signal_ids|source_raw_article_ids|"
                    + "evidence_refs|evidence_id|signal_id|profile_context|input_snapshot|output_payload|"
                    + "top_keyword_evidence|top_keyword_reason|peer_id)\\b|signal:\\d+",
            Pattern.CASE_INSENSITIVE
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Value("${axis.peer-overview.table-cache-ttl-seconds:600}")
    private long tableCacheTtlSeconds;

    private volatile CachedPeerOverviewTable cachedPeerOverviewTable = new CachedPeerOverviewTable(Map.of(), Instant.EPOCH, Instant.EPOCH);

    public Map<String, Object> getPeerOverviewTable() {
        Instant latestDataVersion = loadLatestPeerOverviewDataVersion();
        CachedPeerOverviewTable current = cachedPeerOverviewTable;
        if (!isPeerOverviewTableCacheExpired(current.cachedAt()) && !isPeerOverviewTableDataStale(current, latestDataVersion)) {
            return current.payload();
        }

        synchronized (this) {
            latestDataVersion = loadLatestPeerOverviewDataVersion();
            current = cachedPeerOverviewTable;
            if (!isPeerOverviewTableCacheExpired(current.cachedAt()) && !isPeerOverviewTableDataStale(current, latestDataVersion)) {
                return current.payload();
            }

            Map<String, Object> refreshed = loadPeerOverviewTable(latestDataVersion);
            cachedPeerOverviewTable = new CachedPeerOverviewTable(refreshed, Instant.now(), latestDataVersion);
            return refreshed;
        }
    }

    private Map<String, Object> loadPeerOverviewTable(Instant dataUpdatedAt) {
        String period = resolveCommonPeriod();
        Map<String, SupplementalRow> supplementalRows = loadLlmKeywordRows(period);
        List<Map<String, Object>> rows = loadRows(period, supplementalRows);
        Map<String, PeerLlmAnalysisSnapshot> llmSnapshots = loadPeerLlmAnalysisSnapshots();
        Map<String, List<Map<String, String>>> comparisonInsights = buildComparisonInsights(rows);
        Map<String, List<Map<String, String>>> swotInsights = defaultSwotInsights();
        Map<String, List<Map<String, Object>>> analysisTraces = new LinkedHashMap<>();
        applyPeerLlmSnapshots(llmSnapshots, comparisonInsights, swotInsights, analysisTraces);
        return mapOf(
                "periodLabel", period,
                "coverageLabel", period == null ? "공통 분기 미확보" : "SK AX · 삼성 SDS · LG CNS · 현대 오토에버 · 포스코 DX 공통 분기 기준",
                "financialSourceLabel", "각 사 IR·사업보고서 기반",
                "supplementalSourceLabel", supplementalRows.isEmpty()
                        ? "LLM 키워드 스냅샷 미확보, 미확보 시 -"
                        : "LLM 키워드 스냅샷 기반, 미확보 시 -",
                "comparisonInsights", comparisonInsights,
                "swotInsights", swotInsights,
                "analysisTraces", analysisTraces,
                "dataUpdatedAt", formatDataUpdatedAt(dataUpdatedAt),
                "rows", rows
        );
    }

    private boolean isPeerOverviewTableCacheExpired(Instant cachedAt) {
        if (cachedAt == null || Instant.EPOCH.equals(cachedAt)) {
            return true;
        }
        if (tableCacheTtlSeconds <= 0) {
            return true;
        }
        return Duration.between(cachedAt, Instant.now()).getSeconds() >= tableCacheTtlSeconds;
    }

    private boolean isPeerOverviewTableDataStale(CachedPeerOverviewTable current, Instant latestDataVersion) {
        if (latestDataVersion == null || Instant.EPOCH.equals(latestDataVersion)) {
            return false;
        }
        Instant cachedDataVersion = current.dataVersion();
        return cachedDataVersion == null || latestDataVersion.isAfter(cachedDataVersion);
    }

    private Instant loadLatestPeerOverviewDataVersion() {
        try {
            return jdbcTemplate.query(LATEST_DATA_VERSION_SQL, rs -> {
                if (!rs.next()) {
                    return Instant.EPOCH;
                }
                Timestamp latestAt = rs.getTimestamp("latest_at");
                return latestAt == null ? Instant.EPOCH : latestAt.toInstant();
            });
        } catch (DataAccessException ex) {
            log.warn("PeerOverviewTable | latest snapshot version unavailable, using TTL cache only", ex);
            return Instant.EPOCH;
        }
    }

    public Map<String, Object> getPeerPositioningChart() {
        String period = resolvePositioningCommonPeriod();
        boolean mixedPeriods = period == null;
        List<Map<String, Object>> points = mixedPeriods ? loadLatestPositioningPoints() : loadPositioningPoints(period);
        Instant dataUpdatedAt = loadLatestPeerOverviewDataVersion();
        return mapOf(
                "periodLabel", mixedPeriods ? "peer별 최신 분기" : period,
                "coverageLabel", mixedPeriods
                        ? "매출 + 매출 YoY 공통 분기가 없어 peer별 최신 가용 분기 기준으로 표시"
                        : "SK AX · 삼성 SDS · LG CNS · 현대 오토에버 · 포스코 DX 공통 분기 기준",
                "financialSourceLabel", "각 사 IR·사업보고서 기반",
                "xAxisLabel", "사업 규모 (매출, 억원)",
                "yAxisLabel", "매출 성장률 (YoY, %)",
                "referenceRevenueKrwBn", 30000,
                "referenceGrowthPct", 5,
                "dataUpdatedAt", formatDataUpdatedAt(dataUpdatedAt),
                "points", points
        );
    }

    private String formatDataUpdatedAt(Instant dataUpdatedAt) {
        return dataUpdatedAt == null || Instant.EPOCH.equals(dataUpdatedAt) ? null : dataUpdatedAt.toString();
    }

    private String resolveCommonPeriod() {
        try {
            return jdbcTemplate.query(
                    RESOLVE_RAW_FINANCIAL_COMMON_PERIOD_SQL,
                    ps -> {
                        bindFinancialPeerIds(ps, 1);
                        ps.setInt(FINANCIAL_PEER_IDS.size() + 1, FINANCIAL_PEER_IDS.size());
                    },
                    rs -> rs.next() ? rs.getString("period") : null
            );
        } catch (DataAccessException ex) {
            log.warn("PeerOverviewTable | raw financial metric period unavailable, using peer_financials fallback", ex);
            return resolveCommonPeriodFromPeerFinancials();
        }
    }

    private String resolvePositioningCommonPeriod() {
        try {
            return jdbcTemplate.query(
                    RESOLVE_COMMON_PERIOD_SQL,
                    ps -> {
                        bindFinancialPeerIds(ps, 1);
                        ps.setInt(FINANCIAL_PEER_IDS.size() + 1, FINANCIAL_PEER_IDS.size());
                    },
                    rs -> rs.next() ? rs.getString("period") : null
            );
        } catch (DataAccessException ex) {
            log.warn("PeerOverviewTable | positioning common period unavailable, using latest peer periods", ex);
            return null;
        }
    }

    private List<Map<String, Object>> loadRows(String period, Map<String, SupplementalRow> supplementalRows) {
        List<Map<String, Object>> financialRows = loadFinancialRows(period);
        Map<String, Map<String, Object>> financialRowById = new HashMap<>();
        for (Map<String, Object> row : financialRows) {
            financialRowById.put((String) row.get("id"), row);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (DisplayPeer displayPeer : DISPLAY_PEERS) {
            Map<String, Object> baseRow = new LinkedHashMap<>();
            baseRow.put("id", displayPeer.id());
            baseRow.put("label", displayPeer.label());
            baseRow.put("revenueKrwBn", null);
            baseRow.put("revenueQoqPct", null);
            baseRow.put("operatingProfitKrwBn", null);
            baseRow.put("operatingProfitQoqPct", null);
            baseRow.put("netIncomeKrwBn", null);
            baseRow.put("operatingMarginPct", null);
            baseRow.put("operatingMarginQoqDeltaPctp", null);
            baseRow.put("axRevenueSharePct", null);
            baseRow.put("topKeyword", null);
            baseRow.put("businessKeyword", null);
            baseRow.put("technologyKeyword", null);
            baseRow.put("topKeywordReason", null);
            baseRow.put("topKeywordBasis", null);
            baseRow.put("topKeywordScore", null);
            baseRow.put("topKeywordEvidence", List.of());
            baseRow.put("topKeywordEvidenceUrls", List.of());
            baseRow.put("dartRceptNo", null);

            Map<String, Object> financialRow = financialRowById.get(displayPeer.id());
            if (financialRow != null) {
                baseRow.putAll(financialRow);
            }

            SupplementalRow supplementalRow = supplementalRows.get(displayPeer.id());
            if (supplementalRow != null) {
                if (baseRow.get("label") == null) {
                    baseRow.put("label", supplementalRow.label());
                }
                baseRow.put("axRevenueSharePct", supplementalRow.axRevenueSharePct());
                baseRow.put("topKeyword", supplementalRow.topKeyword());
                baseRow.put("businessKeyword", supplementalRow.businessKeyword());
                baseRow.put("technologyKeyword", supplementalRow.technologyKeyword());
                baseRow.put("topKeywordReason", supplementalRow.topKeywordReason());
                baseRow.put("topKeywordBasis", supplementalRow.topKeywordBasis());
                baseRow.put("topKeywordScore", supplementalRow.topKeywordScore());
                baseRow.put("topKeywordEvidence", supplementalRow.topKeywordEvidence());
                baseRow.put("topKeywordEvidenceUrls", supplementalRow.topKeywordEvidenceUrls());
            }

            rows.add(baseRow);
        }
        return rows;
    }

    private Map<String, List<Map<String, String>>> loadSwotInsights() {
        try {
            Map<String, List<Map<String, String>>> insights = jdbcTemplate.query(SWOT_INSIGHTS_SQL, rs -> {
                Map<String, List<Map<String, String>>> result = new LinkedHashMap<>();
                while (rs.next()) {
                    String peerId = rs.getString("peer_id");
                    String peerName = rs.getString("peer_name");
                    String sourceText = String.join("\n",
                            nullToEmpty(rs.getString("title")),
                            nullToEmpty(rs.getString("content")),
                            nullToEmpty(rs.getString("metadata_text"))
                    );
                    List<Map<String, String>> items = parseSwotItems(peerName, sourceText);
                    if (!items.isEmpty()) {
                        result.put(peerId, items);
                    }
                }
                return result;
            });

            for (DisplayPeer displayPeer : DISPLAY_PEERS) {
                insights.putIfAbsent(displayPeer.id(), missingSwotInsights());
            }
            insights.put("all", missingSwotInsights());
            return insights;
        } catch (DataAccessException ex) {
            log.warn("PeerOverviewTable | SWOT insights unavailable", ex);
            Map<String, List<Map<String, String>>> fallback = new LinkedHashMap<>();
            for (DisplayPeer displayPeer : DISPLAY_PEERS) {
                fallback.put(displayPeer.id(), missingSwotInsights());
            }
            fallback.put("all", missingSwotInsights());
            return fallback;
        }
    }

    private List<Map<String, String>> parseSwotItems(String peerName, String sourceText) {
        Map<String, String> parsed = new LinkedHashMap<>();
        parsed.putAll(parseSwotFromJson(sourceText));
        parsed.putAll(parseSwotFromSections(sourceText));

        List<Map<String, String>> items = new ArrayList<>();
        addSwotItem(items, "Strength", peerName, parsed.get("Strength"));
        addSwotItem(items, "Weakness", peerName, parsed.get("Weakness"));
        addSwotItem(items, "Opportunity", peerName, parsed.get("Opportunity"));
        addSwotItem(items, "Threat", peerName, parsed.get("Threat"));
        return items;
    }

    private Map<String, String> parseSwotFromJson(String sourceText) {
        Map<String, String> result = new LinkedHashMap<>();
        int jsonStart = sourceText.indexOf('{');
        int jsonEnd = sourceText.lastIndexOf('}');
        if (jsonStart < 0 || jsonEnd <= jsonStart) {
            return result;
        }

        String jsonText = sourceText.substring(jsonStart, jsonEnd + 1);
        try {
            Map<String, Object> root = objectMapper.readValue(jsonText, new TypeReference<>() {
            });
            collectSwotJsonValues(root, result);
        } catch (Exception ignored) {
            // Fall back to section parsing below.
        }
        return result;
    }

    private void collectSwotJsonValues(Object value, Map<String, String> result) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String canonical = canonicalSwotLabel(String.valueOf(entry.getKey()));
                if (canonical != null) {
                    String text = flattenSwotValue(entry.getValue());
                    if (!text.isBlank()) {
                        result.putIfAbsent(canonical, text);
                    }
                }
                collectSwotJsonValues(entry.getValue(), result);
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                collectSwotJsonValues(item, result);
            }
        }
    }

    private String flattenSwotValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String stringValue) {
            return normalizeDisplayText(stringValue);
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(this::flattenSwotValue)
                    .filter(text -> !text.isBlank())
                    .reduce((left, right) -> left + " " + right)
                    .orElse("");
        }
        if (value instanceof Map<?, ?> map) {
            return map.values().stream()
                    .map(this::flattenSwotValue)
                    .filter(text -> !text.isBlank())
                    .reduce((left, right) -> left + " " + right)
                    .orElse("");
        }
        return normalizeDisplayText(String.valueOf(value));
    }

    private Map<String, String> parseSwotFromSections(String sourceText) {
        Map<String, String> result = new LinkedHashMap<>();
        parseSwotLines(extractSwotAnalysisSection(sourceText), result);
        parseSwotLines(sourceText, result);
        return result;
    }

    private String extractSwotAnalysisSection(String sourceText) {
        Pattern sectionPattern = Pattern.compile("(?is)(?:^|\\R)\\s*#{1,6}\\s*SWOT\\s*분석\\s*\\R(.*?)(?=\\R\\s*#{1,6}\\s+|$)");
        Matcher sectionMatcher = sectionPattern.matcher(sourceText);
        return sectionMatcher.find() ? sectionMatcher.group(1) : "";
    }

    private void parseSwotLines(String sourceText, Map<String, String> result) {
        if (sourceText == null || sourceText.isBlank()) {
            return;
        }
        String labels = "Strength|Weakness|Opportunity|Threat|강점|약점|기회|위협";
        Pattern pattern = Pattern.compile(
                "(?is)(?:^|[\\n\\r\\{,])\\s*(?:[-*•]\\s*)?(Strength|Weakness|Opportunity|Threat|강점|약점|기회|위협)\\s*[\\]:：\\-]\\s*(.*?)(?=(?:[\\n\\r\\{,])\\s*(?:[-*•]\\s*)?(?:"
                        + labels
                        + ")\\s*[\\]:：\\-]|(?:[\\n\\r])\\s*#{1,6}\\s+|$)"
        );
        Matcher matcher = pattern.matcher(sourceText);
        while (matcher.find()) {
            String canonical = canonicalSwotLabel(matcher.group(1));
            String body = normalizeDisplayText(matcher.group(2));
            if (canonical != null && !body.isBlank()) {
                result.putIfAbsent(canonical, body);
            }
        }
    }

    private void addSwotItem(List<Map<String, String>> items, String label, String peerName, String body) {
        if (body == null || body.isBlank()) {
            return;
        }
        items.add(insight(label, body));
    }

    private List<Map<String, String>> missingSwotInsights() {
        return List.of(
                insight("Strength", "정보 없음"),
                insight("Weakness", "정보 없음"),
                insight("Opportunity", "정보 없음"),
                insight("Threat", "정보 없음")
        );
    }

    private Map<String, List<Map<String, String>>> defaultSwotInsights() {
        Map<String, List<Map<String, String>>> fallback = new LinkedHashMap<>();
        for (DisplayPeer displayPeer : DISPLAY_PEERS) {
            fallback.put(displayPeer.id(), missingSwotInsights());
        }
        fallback.put("all", missingSwotInsights());
        return fallback;
    }

    private String canonicalSwotLabel(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        return switch (normalized) {
            case "strength", "strengths", "강점" -> "Strength";
            case "weakness", "weaknesses", "약점" -> "Weakness";
            case "opportunity", "opportunities", "기회" -> "Opportunity";
            case "threat", "threats", "위협" -> "Threat";
            default -> null;
        };
    }

    private String normalizeDisplayText(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value
                .replaceAll("(?is)<[^>]+>", " ")
                .replaceAll("[\\[\\]\\{\\}\"]", " ")
                .replaceAll("(?m)^\\s*[-*•]\\s*", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.length() > 260) {
            int sentenceEnd = Math.max(cleaned.lastIndexOf(". ", 220), cleaned.lastIndexOf("다. ", 220));
            int cutIndex = sentenceEnd > 80 ? sentenceEnd + 1 : 260;
            cleaned = cleaned.substring(0, Math.min(cutIndex, cleaned.length())).trim();
        }
        return cleaned;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private Map<String, List<Map<String, String>>> buildComparisonInsights(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> rowById = new HashMap<>();
        for (Map<String, Object> row : rows) {
            rowById.put((String) row.get("id"), row);
        }

        Map<String, List<Map<String, String>>> insights = new LinkedHashMap<>();
        insights.put("all", buildAllComparisonInsights(rows));
        for (DisplayPeer peer : DISPLAY_PEERS) {
            if ("sk_ax".equals(peer.id())) {
                continue;
            }
            Map<String, Object> peerRow = rowById.get(peer.id());
            Map<String, Object> skAxRow = rowById.get("sk_ax");
            if (peerRow != null) {
                insights.put(peer.id(), buildPeerComparisonInsights(skAxRow, peerRow));
            }
        }
        return insights;
    }

    private Map<String, PeerLlmAnalysisSnapshot> loadPeerLlmAnalysisSnapshots() {
        try {
            return jdbcTemplate.query(PEER_LLM_ANALYSIS_SNAPSHOTS_SQL, rs -> {
                Map<String, PeerLlmAnalysisSnapshot> snapshots = new LinkedHashMap<>();
                while (rs.next()) {
                    String peerId = rs.getString("peer_id");
                    Map<String, Object> outputPayload = readJsonObject(rs.getString("output_payload"));
                    List<Map<String, Object>> analysisTrace = readJsonList(rs.getString("analysis_trace"));
                    snapshots.put(peerId, new PeerLlmAnalysisSnapshot(peerId, outputPayload, analysisTrace));
                }
                return snapshots;
            });
        } catch (DataAccessException ex) {
            log.warn("PeerOverviewTable | LLM analysis snapshots unavailable", ex);
            return Map.of();
        }
    }

    private void applyPeerLlmSnapshots(
            Map<String, PeerLlmAnalysisSnapshot> snapshots,
            Map<String, List<Map<String, String>>> comparisonInsights,
            Map<String, List<Map<String, String>>> swotInsights,
            Map<String, List<Map<String, Object>>> analysisTraces
    ) {
        for (PeerLlmAnalysisSnapshot snapshot : snapshots.values()) {
            List<Map<String, String>> comparisonItems = extractComparisonPoints(snapshot.outputPayload());
            if (!comparisonItems.isEmpty()) {
                comparisonInsights.put(snapshot.peerId(), comparisonItems);
            }

            List<Map<String, String>> swotItems = extractSwotItems(snapshot.outputPayload());
            if (!swotItems.isEmpty()) {
                swotInsights.put(snapshot.peerId(), swotItems);
            }

            List<Map<String, Object>> traceItems = extractAnalysisTrace(snapshot);
            if (!traceItems.isEmpty()) {
                analysisTraces.put(snapshot.peerId(), traceItems);
            }
        }
    }

    private List<Map<String, String>> extractComparisonPoints(Map<String, Object> outputPayload) {
        List<Map<String, String>> items = new ArrayList<>();
        for (Object rawItem : listValue(outputPayload.get("comparison_points"))) {
            Map<String, Object> item = objectMap(rawItem);
            String label = stringValue(item.get("label"));
            String body = sanitizeObjectivePeerFlowText(stringValue(item.get("body")));
            if (List.of("포지셔닝", "사업 신호", "기술 신호", "리스크").contains(label) && !body.isBlank()) {
                Map<String, String> comparisonItem = insight(label, body);
                String reasoningSummary = sanitizeObjectivePeerFlowText(stringValue(item.get("reasoning_summary")));
                String evidenceSummary = sanitizeObjectivePeerFlowText(stringValue(item.get("evidence_summary")));
                if (!reasoningSummary.isBlank()) {
                    comparisonItem.put("reasoningSummary", reasoningSummary);
                }
                if (!evidenceSummary.isBlank()) {
                    comparisonItem.put("evidenceSummary", evidenceSummary);
                }
                items.add(comparisonItem);
            }
        }
        return items;
    }

    private List<Map<String, String>> extractSwotItems(Map<String, Object> outputPayload) {
        List<Map<String, String>> items = new ArrayList<>();
        List<Object> sourceItems = listValue(outputPayload.get("swot"));
        if (sourceItems.isEmpty()) {
            sourceItems = listValue(outputPayload.get("swot_monitoring_axes"));
        }
        for (Object rawItem : sourceItems) {
            Map<String, Object> item = objectMap(rawItem);
            String label = stringValue(item.get("label"));
            String body = buildSwotDisplayBody(item);
            if (canonicalSwotLabel(label) != null && !body.isBlank()) {
                Map<String, String> swotItem = insight(canonicalSwotLabel(label), body);
                String title = sanitizeObjectivePeerFlowText(firstNonBlank(
                        stringValue(item.get("title")),
                        stringValue(item.get("axis_name")),
                        stringValue(item.get("axisName"))
                ));
                String factorType = stringValue(firstNonBlank(
                        stringValue(item.get("factor_type")),
                        stringValue(item.get("factorType")),
                        defaultSwotFactorType(canonicalSwotLabel(label))
                ));
                String reasoningSummary = sanitizeObjectivePeerFlowText(stringValue(item.get("reasoning_summary")));
                String evidenceSummary = sanitizeObjectivePeerFlowText(stringValue(item.get("evidence_summary")));
                if (!title.isBlank()) {
                    swotItem.put("title", title);
                }
                if (!factorType.isBlank()) {
                    swotItem.put("factorType", factorType);
                }
                if (!reasoningSummary.isBlank()) {
                    swotItem.put("reasoningSummary", reasoningSummary);
                }
                if (!evidenceSummary.isBlank()) {
                    swotItem.put("evidenceSummary", evidenceSummary);
                }
                String checkPoint = buildSwotCheckPoint(item);
                if (!checkPoint.isBlank()) {
                    swotItem.put("checkPoint", checkPoint);
                }
                items.add(swotItem);
            }
        }
        return items;
    }

    private String buildSwotDisplayBody(Map<String, Object> item) {
        String label = canonicalSwotLabel(stringValue(item.get("label")));
        String body = sanitizeObjectivePeerFlowText(stringValue(item.get("body")));
        if (!body.isBlank() && !looksLikeAxisExplanation(body) && !looksLikeGenericSwotDiagnosis(body)) {
            return normalizeSwotDisplayText(body);
        }
        boolean insufficientEvidence = Boolean.parseBoolean(stringValue(item.get("insufficient_evidence")))
                || Boolean.parseBoolean(stringValue(item.get("insufficientEvidence")));
        String axisDefinition = sanitizeObjectivePeerFlowText(firstNonBlank(
                stringValue(item.get("axis_definition")),
                stringValue(item.get("axisDefinition"))
        ));
        String whyMonitor = sanitizeObjectivePeerFlowText(firstNonBlank(
                stringValue(item.get("why_monitor")),
                stringValue(item.get("whyMonitor"))
        ));
        String supportingPattern = sanitizeObjectivePeerFlowText(firstNonBlank(
                stringValue(item.get("supporting_pattern")),
                stringValue(item.get("supportingPattern"))
        ));
        if (insufficientEvidence || isInsufficientSwotText(axisDefinition)) {
            return "판단 근거가 부족합니다.";
        }
        String title = sanitizeObjectivePeerFlowText(firstNonBlank(
                stringValue(item.get("title")),
                stringValue(item.get("axis_name")),
                stringValue(item.get("axisName"))
        ));
        String evidenceSummary = sanitizeObjectivePeerFlowText(stringValue(item.get("evidence_summary")));
        String reasoningSummary = sanitizeObjectivePeerFlowText(stringValue(item.get("reasoning_summary")));
        String diagnosisBody = buildSwotDiagnosisBody(
                label,
                title,
                supportingPattern,
                evidenceSummary,
                reasoningSummary,
                axisDefinition
        );
        if (!diagnosisBody.isBlank()) {
            return normalizeSwotDisplayText(diagnosisBody);
        }
        if (axisDefinition.equals(whyMonitor)) {
            whyMonitor = "";
        }
        String combined = firstNonBlank(joinDisplaySentences(axisDefinition, whyMonitor), supportingPattern);
        if (!combined.isBlank()) {
            return normalizeSwotDisplayText(combined);
        }
        return normalizeSwotDisplayText(sanitizeObjectivePeerFlowText(firstNonBlank(
                stringValue(item.get("axis_name")),
                stringValue(item.get("axisName")),
                stringValue(item.get("title"))
        )));
    }

    private String buildSwotDiagnosisBody(
            String label,
            String title,
            String supportingPattern,
            String evidenceSummary,
            String reasoningSummary,
            String axisDefinition
    ) {
        String detail = firstNonAxisText(supportingPattern, evidenceSummary, reasoningSummary, axisDefinition);
        String subject = firstNonBlank(
                isGenericSwotTitle(title) ? "" : title,
                swotSubjectFromDetail(detail),
                switch (label == null ? "" : label) {
            case "Strength" -> "내부 역량";
            case "Weakness" -> "내부 개선 과제";
            case "Opportunity" -> "외부 성장 기회";
            case "Threat" -> "외부 위협 요인";
            default -> "SWOT 요인";
        });
        String sentence = switch (label == null ? "" : label) {
            case "Strength" -> subject + topicParticle(subject) + " 내부 강점으로 확인됩니다.";
            case "Weakness" -> subject + topicParticle(subject) + " 내부 개선 과제로 남아 있습니다.";
            case "Opportunity" -> subject + topicParticle(subject) + " 외부 기회 요인으로 볼 수 있습니다.";
            case "Threat" -> subject + topicParticle(subject) + " 외부 위협 요인으로 볼 수 있습니다.";
            default -> subject + topicParticle(subject) + " 주요 SWOT 요인으로 볼 수 있습니다.";
        };
        if (!detail.isBlank()) {
            sentence = sentence + " " + detail;
        }
        return sentence;
    }

    private boolean isGenericSwotTitle(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        return List.of(
                "내부 역량",
                "내부 역량 강화",
                "내부 역량 통합성",
                "내부 역량의 반복 적용성",
                "내부 제약",
                "내부 제약 관리",
                "외부 시장 기회",
                "시장 기회",
                "시장 기회 탐색",
                "시장 성장 가능성",
                "외부 환경 압박",
                "외부 환경의 부정적 압박",
                "외부 환경의 유리한 조건",
                "경쟁 및 규제 압박",
                "외부 기회",
                "외부 위협",
                "외부 경쟁 위협",
                "외부 압박 요인",
                "외부 경쟁 압박",
                "수익성 개선",
                "수익성 개선 필요",
                "수익성 개선 필요성",
                "사업 성장 가능성",
                "성장 가능성"
        ).contains(value.trim());
    }

    private String swotSubjectFromDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            return "";
        }
        List<String> patterns = List.of(
                "GPUaaS",
                "패브릭스",
                "클라우드",
                "AI",
                "IT 서비스",
                "스마트 엔지니어링",
                "스마트 물류",
                "제조 AX",
                "차량용 소프트웨어",
                "차량 SW",
                "R&D 비용",
                "CCS",
                "OTA",
                "내비게이션 플랫폼",
                "데이터센터",
                "Intelligent Factory",
                "로봇",
                "공장 자동화",
                "영업이익"
        );
        List<String> matched = new ArrayList<>();
        for (String pattern : patterns) {
            if (detail.contains(pattern) && !matched.contains(pattern)) {
                matched.add(pattern);
            }
            if (matched.size() >= 2) {
                break;
            }
        }
        if (!matched.isEmpty()) {
            return String.join("·", matched);
        }
        Matcher matcher = Pattern.compile("[A-Za-z][A-Za-z0-9+&./-]{2,}|[가-힣0-9A-Za-z+&./-]{3,}").matcher(detail);
        List<String> tokens = new ArrayList<>();
        while (matcher.find() && tokens.size() < 2) {
            String token = matcher.group();
            if (!List.of("관련", "기반", "최근", "변화", "확대", "강화", "관찰됨", "나타나고").contains(token)) {
                tokens.add(token);
            }
        }
        return String.join("·", tokens);
    }

    private String topicParticle(String value) {
        if (value == null || value.isBlank()) {
            return "은";
        }
        char lastChar = value.charAt(value.length() - 1);
        if (lastChar >= 0xAC00 && lastChar <= 0xD7A3) {
            return ((lastChar - 0xAC00) % 28) == 0 ? "는" : "은";
        }
        return "는";
    }

    private String firstNonAxisText(String... values) {
        for (String value : values) {
            String cleaned = sanitizeObjectivePeerFlowText(value);
            if (!cleaned.isBlank() && !looksLikeAxisExplanation(cleaned)) {
                return cleaned;
            }
        }
        return "";
    }

    private boolean looksLikeAxisExplanation(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return value.contains("이 축은")
                || value.contains("관찰 축")
                || value.contains("판단하기 위한 기준")
                || value.contains("판단 기준")
                || value.contains("추적해야")
                || value.contains("모니터링");
    }

    private boolean looksLikeGenericSwotDiagnosis(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String cleaned = sanitizeObjectivePeerFlowText(value);
        boolean hasGenericFrame = cleaned.contains("내부 강점으로 확인")
                || cleaned.contains("내부 개선 과제로 남")
                || cleaned.contains("외부 기회 요인으로 볼 수")
                || cleaned.contains("외부 위협 요인으로 볼 수");
        if (!hasGenericFrame) {
            return false;
        }
        return cleaned.startsWith("내부 역량")
                || cleaned.startsWith("내부 제약")
                || cleaned.startsWith("외부 시장")
                || cleaned.startsWith("시장 기회")
                || cleaned.startsWith("외부 환경")
                || cleaned.startsWith("경쟁 및 규제")
                || cleaned.startsWith("수익성 개선")
                || cleaned.startsWith("시장 성장");
    }

    private String buildSwotCheckPoint(Map<String, Object> item) {
        String explicit = sanitizeObjectivePeerFlowText(firstNonBlank(
                stringValue(item.get("check_point")),
                stringValue(item.get("checkPoint"))
        ));
        if (!explicit.isBlank()) {
            return explicit;
        }
        String watchVariables = flattenSwotValue(firstNonBlankObject(
                item.get("watch_variables"),
                item.get("watchVariables")
        ));
        return sanitizeObjectivePeerFlowText(watchVariables);
    }

    private String joinNonBlank(String... values) {
        List<String> parts = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                parts.add(value);
            }
        }
        return String.join(" ", parts);
    }

    private String joinDisplaySentences(String... values) {
        List<String> parts = new ArrayList<>();
        for (String value : values) {
            String cleaned = sanitizeObjectivePeerFlowText(value);
            if (cleaned.isBlank()) {
                continue;
            }
            if (!parts.isEmpty()) {
                int lastIndex = parts.size() - 1;
                String previous = parts.get(lastIndex);
                if (!previous.endsWith(".") && !previous.endsWith("!") && !previous.endsWith("?") && !previous.endsWith("。")) {
                    parts.set(lastIndex, previous + ".");
                }
            }
            parts.add(cleaned);
        }
        return String.join(" ", parts);
    }

    private boolean isInsufficientSwotText(String value) {
        return value != null && (
                value.contains("현재 입력 근거만으로 해당 축을 정의하기 어렵다")
                        || value.contains("판단 근거 부족")
        );
    }

    private String normalizeSwotDisplayText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String cleaned = sanitizeObjectivePeerFlowText(value)
                .replaceAll("[\\[\\]\\{\\}\"]", " ")
                .replaceAll("(?m)^\\s*[-*•]\\s*", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.isBlank()) {
            return "";
        }

        List<String> uniqueSentences = new ArrayList<>();
        for (String sentence : cleaned.split("(?<=[.!?。])\\s+")) {
            String normalized = sentence.replaceAll("\\s+", " ").trim();
            if (!normalized.isBlank() && !uniqueSentences.contains(normalized)) {
                uniqueSentences.add(normalized);
            }
        }
        return String.join(" ", uniqueSentences);
    }

    private Object firstNonBlankObject(Object... values) {
        for (Object value : values) {
            String text = stringValue(value);
            if (!text.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String defaultSwotFactorType(String label) {
        if (label == null) {
            return "";
        }
        return switch (label) {
            case "Strength", "Weakness" -> "internal_controllable";
            case "Opportunity", "Threat" -> "external_uncontrollable";
            default -> "";
        };
    }

    private List<Map<String, Object>> extractAnalysisTrace(PeerLlmAnalysisSnapshot snapshot) {
        List<Map<String, Object>> items = new ArrayList<>();
        List<Map<String, Object>> sourceItems = snapshot.analysisTrace().isEmpty()
                ? objectList(snapshot.outputPayload().get("analysis_trace"))
                : snapshot.analysisTrace();
        for (Map<String, Object> item : sourceItems) {
            String step = stringValue(item.get("step"));
            String summary = sanitizeObjectivePeerFlowText(stringValue(item.get("summary")));
            String reasoning = sanitizeObjectivePeerFlowText(firstNonBlank(
                    stringValue(item.get("reasoning")),
                    stringValue(item.get("reasoning_summary")),
                    stringValue(item.get("interpretation"))
            ));
            String evidence = sanitizeObjectivePeerFlowText(firstNonBlank(
                    stringValue(item.get("evidence")),
                    stringValue(item.get("evidence_summary")),
                    stringValue(item.get("basis")),
                    stringValue(item.get("source_summary"))
            ));
            if (!step.isBlank() && (!summary.isBlank() || !reasoning.isBlank() || !evidence.isBlank())) {
                items.add(traceItem(step, summary, reasoning, evidence));
            }
        }
        return items;
    }

    private Map<String, Object> readJsonObject(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() {
            });
        } catch (Exception ex) {
            log.warn("PeerOverviewTable | failed to parse LLM output payload", ex);
            return Map.of();
        }
    }

    private List<Map<String, Object>> readJsonList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() {
            });
        } catch (Exception ex) {
            log.warn("PeerOverviewTable | failed to parse LLM analysis trace", ex);
            return List.of();
        }
    }

    private List<Object> listValue(Object value) {
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return List.of();
    }

    private List<Map<String, Object>> objectList(Object value) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object rawItem : listValue(value)) {
            Map<String, Object> item = objectMap(rawItem);
            if (!item.isEmpty()) {
                items.add(item);
            }
        }
        return items;
    }

    private Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }
        return Map.of();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String sanitizeObjectivePeerFlowText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value
                .replace("최근 공개 원문에서는", "")
                .replace("최근 공개 원문에서", "최근 신호에서")
                .replace("최근 공개 원문 신호", "최근 신호")
                .replace("이 Peer사는", "해당 기업은")
                .replace("이 Peer사가", "해당 기업이")
                .replace("이 Peer사를", "해당 기업을")
                .replace("이 Peer사의", "해당 기업의")
                .replace("이 peer사는", "해당 기업은")
                .replace("이 peer사가", "해당 기업이")
                .replace("이 peer사를", "해당 기업을")
                .replace("이 peer사의", "해당 기업의")
                .replace("Peer사는", "해당 기업은")
                .replace("Peer사가", "해당 기업이")
                .replace("Peer사를", "해당 기업을")
                .replace("Peer사의", "해당 기업의")
                .replace("Peer사에", "해당 기업에")
                .replace("Peer사", "대상 기업")
                .replace("peer사는", "해당 기업은")
                .replace("peer사가", "해당 기업이")
                .replace("peer사를", "해당 기업을")
                .replace("peer사의", "해당 기업의")
                .replace("peer사에", "해당 기업에")
                .replace("peer사", "대상 기업")
                .replace("현재 입력 근거만으로 해당 축을 정의하기 어렵다", "판단 근거가 부족합니다.")
                .replace("SK AX와 비교했을 때", "")
                .replace("SK AX와 비교해", "")
                .replace("SK AX와 비교하면", "")
                .replace("SK AX 대비", "")
                .replace("SK AX 기준", "")
                .replace("SK AX 관점에서", "")
                .replace("SK AX는", "해당 기업은")
                .replace("SK AX의", "해당 기업의")
                .replace("자사", "해당 기업")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private List<Map<String, String>> buildAllComparisonInsights(List<Map<String, Object>> rows) {
        List<Map<String, Object>> peerRows = rows.stream()
                .filter(row -> !"sk_ax".equals(row.get("id")))
                .toList();
        String businessSummary = peerRows.stream()
                .map(row -> row.get("label") + "는 " + nullToDash(row.get("businessKeyword")))
                .reduce((left, right) -> left + ", " + right)
                .orElse("최근 사업 신호가 충분히 확보되지 않았습니다");
        String techSummary = peerRows.stream()
                .map(row -> row.get("label") + "는 " + nullToDash(row.get("technologyKeyword")))
                .reduce((left, right) -> left + ", " + right)
                .orElse("최근 기술 신호가 충분히 확보되지 않았습니다");
        String marginLeader = peerRows.stream()
                .filter(row -> nullableDouble(row.get("operatingMarginPct")) != null)
                .max((left, right) -> Double.compare(
                        nullableDouble(left.get("operatingMarginPct")),
                        nullableDouble(right.get("operatingMarginPct"))
                ))
                .map(row -> row.get("label") + " " + formatPercentText(nullableDouble(row.get("operatingMarginPct"))))
                .orElse("기업별 수익성 데이터가 제한적");

        return List.of(
                insight("포지셔닝", "전체 비교에서는 peer별 최근 사업·기술 신호가 어디에 집중되는지 보는 것이 중요합니다. " + businessSummary + " 축으로 갈라져 각 기업의 관심 영역과 비교 기준이 드러납니다."),
                insight("사업 신호", "최근 신호를 보면 " + businessSummary + " 관련 활동이 핵심 사업 차이로 나타납니다. 각 기업의 사업명과 고객 산업이 분기별 진행 방향을 보여주는 객관 지표로 쓰입니다."),
                insight("기술 신호", "기술 축에서는 " + techSummary + " 흐름이 보입니다. 제품·플랫폼·구현 역량이 각 peer의 기술 방향을 구분하는 기준입니다."),
                insight("리스크", "Peer를 비교할 때는 최근 사업 신호와 공시 수치를 함께 봐야 합니다. 현재 수익성 기준으로는 " + marginLeader + "가 두드러지며, 세부 부문 공시 범위 차이는 비교 리스크로 남아 있습니다.")
        );
    }

    private List<Map<String, String>> buildPeerComparisonInsights(Map<String, Object> skAxRow, Map<String, Object> peerRow) {
        String peerLabel = String.valueOf(peerRow.get("label"));
        String peerBusiness = nullToDash(peerRow.get("businessKeyword"));
        String peerTech = nullToDash(peerRow.get("technologyKeyword"));
        Double revenue = nullableDouble(peerRow.get("revenueKrwBn"));
        Double margin = nullableDouble(peerRow.get("operatingMarginPct"));
        Double marginDelta = nullableDouble(peerRow.get("operatingMarginQoqDeltaPctp"));

        return List.of(
                insight("포지셔닝", peerLabel + "는 사업 신호의 " + peerBusiness + ", 기술 신호의 " + peerTech + "를 앞세우는 흐름으로 읽힙니다. 분기별 사업명과 기술명이 확인해야 할 초점을 나눕니다."),
                insight("사업 신호", peerLabel + "는 최근 신호에서 " + peerBusiness + " 관련 사업 활동이 가장 강하게 읽힙니다. 수주·확장·고객 산업 같은 실행 신호가 사업 방향 판단의 기준입니다."),
                insight("기술 신호", peerLabel + "는 " + peerTech + "를 제품·플랫폼 또는 구현 역량의 중심으로 보여줍니다. 기술명과 플랫폼 신호가 기술 방향의 객관 지표로 쓰입니다."),
                insight("리스크", buildRiskInsight(peerLabel, revenue, margin, marginDelta))
        );
    }

    private Map<String, String> insight(String label, String body) {
        Map<String, String> item = new LinkedHashMap<>();
        item.put("label", label);
        item.put("body", body);
        return item;
    }

    private Map<String, Object> traceItem(String label, String body, String reasoning, String evidence) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("label", label);
        item.put("body", body);
        if (reasoning != null && !reasoning.isBlank()) {
            item.put("reasoning", reasoning);
        }
        if (evidence != null && !evidence.isBlank()) {
            item.put("evidence", evidence);
        }
        return item;
    }

    private String buildRiskInsight(String peerLabel, Double revenue, Double margin, Double marginDelta) {
        String revenueText = revenue == null ? "매출 데이터가 제한적" : "매출 " + formatKrwBnText(revenue);
        String marginText = margin == null ? "영업이익률 데이터가 제한적" : "영업이익률 " + formatPercentText(margin);
        String deltaText = marginDelta == null ? "전분기 대비 수익성 변화는 확인이 제한적입니다" : "전분기 대비 영업이익률 변화는 " + formatPercentPointText(marginDelta) + "입니다";
        return peerLabel + "는 " + revenueText + ", " + marginText + " 기준으로 함께 봐야 합니다. " + deltaText + ". 따라서 최근 사업·기술 신호가 강하더라도 실적 범위와 수익성 변동은 별도 리스크로 남습니다.";
    }

    private String nullToDash(Object value) {
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            return stringValue;
        }
        return "-";
    }

    private String formatKrwBnText(Double value) {
        if (value == null) {
            return "-";
        }
        if (Math.abs(value) >= 10_000) {
            return String.format("%.2f조원", value / 10_000.0);
        }
        return String.format("%.0f억원", value);
    }

    private String formatPercentText(Double value) {
        if (value == null) {
            return "-";
        }
        return String.format("%.2f%%", value);
    }

    private String formatPercentPointText(Double value) {
        if (value == null) {
            return "-";
        }
        String sign = value > 0 ? "+" : "";
        return sign + String.format("%.2f%%p", value);
    }

    private List<Map<String, Object>> loadFinancialRows(String period) {
        if (period == null || period.isBlank()) {
            return List.of();
        }

        try {
            return jdbcTemplate.query(
                    RAW_FINANCIAL_ROWS_SQL,
                    ps -> {
                        bindFinancialPeerIds(ps, 1);
                        ps.setString(FINANCIAL_PEER_IDS.size() + 1, period);
                    },
                    (rs, rowNum) -> mapFinancialRow(rs)
            );
        } catch (DataAccessException ex) {
            log.warn("PeerOverviewTable | raw financial rows unavailable, using peer_financials fallback", ex);
            return loadPeerFinancialRows(period);
        }
    }

    private String resolveCommonPeriodFromPeerFinancials() {
        return jdbcTemplate.query(
                RESOLVE_PEER_FINANCIALS_COMMON_PERIOD_SQL,
                ps -> {
                    bindFinancialPeerIds(ps, 1);
                    ps.setInt(FINANCIAL_PEER_IDS.size() + 1, FINANCIAL_PEER_IDS.size());
                },
                rs -> rs.next() ? rs.getString("period") : null
        );
    }

    private List<Map<String, Object>> loadPeerFinancialRows(String period) {
        if (period == null || period.isBlank()) {
            return List.of();
        }

        return jdbcTemplate.query(
                PEER_FINANCIAL_ROWS_SQL,
                ps -> {
                    bindFinancialPeerIds(ps, 1);
                    ps.setString(FINANCIAL_PEER_IDS.size() + 1, period);
                },
                (rs, rowNum) -> mapFinancialRow(rs)
        );
    }

    private List<Map<String, Object>> loadPositioningPoints(String period) {
        if (period == null || period.isBlank()) {
            return List.of();
        }

        return queryPositioningPoints(
                POSITIONING_POINTS_SQL,
                ps -> {
                    bindFinancialPeerIds(ps, 1);
                    ps.setString(FINANCIAL_PEER_IDS.size() + 1, period);
                },
                "PeerOverviewTable | positioning points unavailable for period=" + period
        );
    }

    private List<Map<String, Object>> loadLatestPositioningPoints() {
        return queryPositioningPoints(
                LATEST_POSITIONING_POINTS_SQL,
                ps -> bindFinancialPeerIds(ps, 1),
                "PeerOverviewTable | latest positioning points unavailable"
        );
    }

    private List<Map<String, Object>> queryPositioningPoints(
            String sql,
            PositioningQueryBinder binder,
            String failureMessage
    ) {
        Map<String, DisplayPeer> displayPeerById = displayPeerById();
        try {
            return jdbcTemplate.query(
                    sql,
                    ps -> binder.bind(ps),
                    (rs, rowNum) -> mapPositioningPoint(rs, displayPeerById)
            );
        } catch (DataAccessException ex) {
            log.warn(failureMessage, ex);
            return List.of();
        }
    }

    private Map<String, DisplayPeer> displayPeerById() {
        Map<String, DisplayPeer> displayPeerById = new HashMap<>();
        for (DisplayPeer displayPeer : DISPLAY_PEERS) {
            displayPeerById.put(displayPeer.id(), displayPeer);
        }
        return displayPeerById;
    }

    @FunctionalInterface
    private interface PositioningQueryBinder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private Map<String, SupplementalRow> loadLlmKeywordRows(String period) {
        try {
            return jdbcTemplate.query(
                    LLM_KEYWORD_ROWS_SQL,
                    rs -> {
                        Map<String, SupplementalRow> rows = new HashMap<>();
                        while (rs.next()) {
                            Map<String, Object> payload = readJsonObject(rs.getString("output_payload"));
                            String peerId = rs.getString("peer_id");
                            SupplementalRow row = mapLlmKeywordRow(payload, rs.getObject("confidence"));
                            if (row != null) {
                                rows.put(peerId, row);
                            }
                        }
                        return rows;
                    }
            );
        } catch (DataAccessException ex) {
            log.warn("PeerOverviewTable | LLM keyword snapshots unavailable, fallback rows only", ex);
            return Map.of();
        }
    }

    private SupplementalRow mapLlmKeywordRow(Map<String, Object> payload, Object confidenceValue) {
        if (payload.isEmpty()) {
            return null;
        }

        Map<String, Object> businessKeyword = objectMap(payload.get("business_keyword"));
        Map<String, Object> technologyKeyword = objectMap(payload.get("technology_keyword"));
        String businessLabel = stringValue(businessKeyword.get("label"));
        String technologyLabel = stringValue(technologyKeyword.get("label"));
        String topKeyword = stringValue(payload.get("top_keyword"));
        if (topKeyword.isBlank()) {
            topKeyword = String.join("\n", List.of(businessLabel, technologyLabel).stream()
                    .filter(value -> value != null && !value.isBlank())
                    .toList());
        }

        Double score = nullableDouble(confidenceValue);
        if (score == null) {
            Double businessConfidence = nullableDouble(businessKeyword.get("confidence"));
            Double technologyConfidence = nullableDouble(technologyKeyword.get("confidence"));
            if (businessConfidence != null && technologyConfidence != null) {
                score = (businessConfidence + technologyConfidence) / 2.0;
            } else if (businessConfidence != null) {
                score = businessConfidence;
            } else {
                score = technologyConfidence;
            }
        }

        return new SupplementalRow(
                stringValue(payload.get("peer_name")),
                null,
                topKeyword.isBlank() ? null : topKeyword,
                businessLabel.isBlank() ? null : businessLabel,
                technologyLabel.isBlank() ? null : technologyLabel,
                blankToNull(publicEvidenceText(stringValue(payload.get("top_keyword_reason")))),
                blankToNull(publicEvidenceText(stringValue(payload.get("top_keyword_basis")))),
                score,
                buildLlmKeywordEvidenceLines(payload, businessKeyword, technologyKeyword),
                stringList(payload.get("top_keyword_evidence_urls"))
        );
    }

    private List<String> buildLlmKeywordEvidenceLines(
            Map<String, Object> payload,
            Map<String, Object> businessKeyword,
            Map<String, Object> technologyKeyword
    ) {
        List<String> storedLines = stringList(payload.get("top_keyword_evidence"));
        if (!storedLines.isEmpty() && storedLines.stream().noneMatch(this::containsInternalEvidenceMarker)) {
            List<String> publicLines = storedLines.stream()
                    .map(this::publicEvidenceText)
                    .filter(value -> !value.isBlank())
                    .toList();
            if (!publicLines.isEmpty()) {
                return publicLines;
            }
        }

        List<String> generated = new ArrayList<>();
        addLlmKeywordEvidenceLine(generated, payload, businessKeyword, "사업");
        addLlmKeywordEvidenceLine(generated, payload, technologyKeyword, "기술");
        return generated;
    }

    private void addLlmKeywordEvidenceLine(
            List<String> lines,
            Map<String, Object> payload,
            Map<String, Object> keyword,
            String axisLabel
    ) {
        String label = stringValue(keyword.get("label"));
        if (label.isBlank()) {
            return;
        }
        String peerName = stringValue(payload.get("peer_name"));
        String evidenceSummary = firstNonBlank(
                stringValue(keyword.get("evidence_summary")),
                stringValue(keyword.get("reason"))
        );
        String reason = firstNonBlank(
                stringValue(keyword.get("reasoning")),
                stringValue(keyword.get("reason")),
                stringValue(payload.get("top_keyword_reason"))
        );
        lines.add(
                "%s %s 키워드 기준: %s. 근거 내용: %s. 판단 이유: %s"
                        .formatted(
                                peerName.isBlank() ? "해당 기업" : peerName,
                                axisLabel,
                                label,
                                evidenceSummary.isBlank() ? "저장된 근거 요약 없음" : publicEvidenceText(evidenceSummary),
                                reason.isBlank()
                                        ? "이 원문 내용을 보아 해당 기업의 진행 방향을 보여주는 대표 키워드로 선정했습니다."
                                        : publicEvidenceText(reason)
                        )
        );
    }

    private boolean containsInternalEvidenceMarker(String value) {
        return value != null && INTERNAL_EVIDENCE_MARKER.matcher(value).find();
    }

    private String publicEvidenceText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String cleaned = INTERNAL_EVIDENCE_MARKER.matcher(value).replaceAll("공개 근거");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        cleaned = cleaned.replaceAll("(공개 근거[와과, ]*){2,}", "공개 근거 ");
        return cleaned.replaceAll("^[,;\\s]+|[,;\\s]+$", "");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private Map<String, Object> mapFinancialRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getString("id"));
        row.put("revenueKrwBn", nullableDouble(rs.getObject("revenue_total_krwbn")));
        row.put("revenueQoqPct", nullableDouble(rs.getObject("revenue_qoq_pct")));
        row.put("operatingProfitKrwBn", nullableDouble(rs.getObject("operating_profit_krwbn")));
        row.put("operatingProfitQoqPct", nullableDouble(rs.getObject("operating_profit_qoq_pct")));
        row.put("netIncomeKrwBn", nullableDouble(rs.getObject("net_income_krwbn")));
        row.put("netIncomeQoqPct", nullableDouble(rs.getObject("net_income_qoq_pct")));
        row.put("operatingMarginPct", nullableDouble(rs.getObject("operating_margin_pct")));
        row.put("operatingMarginQoqDeltaPctp", nullableDouble(rs.getObject("operating_margin_qoq_delta_pctp")));
        row.put("dartRceptNo", rs.getString("dart_rcept_no"));
        return row;
    }

    private Map<String, Object> mapPositioningPoint(ResultSet rs, Map<String, DisplayPeer> displayPeerById) throws SQLException {
        String id = rs.getString("id");
        DisplayPeer displayPeer = displayPeerById.get(id);

        Map<String, Object> point = new LinkedHashMap<>();
        point.put("id", id);
        point.put("label", displayPeer == null ? id : displayPeer.label());
        point.put("periodLabel", rs.getString("period"));
        point.put("x", nullableDouble(rs.getObject("revenue_krwbn")));
        point.put("y", nullableDouble(rs.getObject("revenue_yoy_pct")));
        point.put("revenueKrwBn", nullableDouble(rs.getObject("revenue_krwbn")));
        point.put("revenueYoyPct", nullableDouble(rs.getObject("revenue_yoy_pct")));
        point.put("revenueSourceType", rs.getString("revenue_source_type"));
        point.put("revenueYoySourceType", rs.getString("revenue_yoy_source_type"));
        point.put("revenueConfidence", nullableDouble(rs.getObject("revenue_confidence")));
        point.put("revenueYoyConfidence", nullableDouble(rs.getObject("revenue_yoy_confidence")));
        point.put("revenueArticleId", nullableLong(rs.getObject("revenue_article_id")));
        point.put("revenueYoyArticleId", nullableLong(rs.getObject("revenue_yoy_article_id")));
        point.put("isSelf", "sk_ax".equals(id));
        return point;
    }

    private void bindFinancialPeerIds(java.sql.PreparedStatement statement, int startIndex) throws SQLException {
        for (int offset = 0; offset < FINANCIAL_PEER_IDS.size(); offset++) {
            statement.setString(startIndex + offset, FINANCIAL_PEER_IDS.get(offset));
        }
    }

    private Double nullableDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Double doubleValue) {
            return doubleValue;
        }
        if (value instanceof BigDecimal decimalValue) {
            return decimalValue.doubleValue();
        }
        if (value instanceof Number numberValue) {
            return numberValue.doubleValue();
        }
        return null;
    }

    private Long nullableLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Long longValue) {
            return longValue;
        }
        if (value instanceof Integer integerValue) {
            return integerValue.longValue();
        }
        if (value instanceof BigDecimal decimalValue) {
            return decimalValue.longValue();
        }
        if (value instanceof Number numberValue) {
            return numberValue.longValue();
        }
        return null;
    }

    private List<String> nullableStringList(Object value) throws SQLException {
        if (value == null) {
            return List.of();
        }
        if (value instanceof java.sql.Array sqlArray) {
            Object array = sqlArray.getArray();
            if (array instanceof String[] strings) {
                List<String> result = new ArrayList<>();
                for (String item : strings) {
                    if (item != null) {
                        result.add(item);
                    }
                }
                return result;
            }
        }
        return List.of();
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            String textValue = stringValue(item);
            if (!textValue.isBlank()) {
                result.add(textValue);
            }
        }
        return result;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record DisplayPeer(int displayOrder, String id, String label) {
    }

    private record SupplementalRow(
            String label,
            Double axRevenueSharePct,
            String topKeyword,
            String businessKeyword,
            String technologyKeyword,
            String topKeywordReason,
            String topKeywordBasis,
            Double topKeywordScore,
            List<String> topKeywordEvidence,
            List<String> topKeywordEvidenceUrls
    ) {
    }

    private record CachedPeerOverviewTable(
            Map<String, Object> payload,
            Instant cachedAt,
            Instant dataVersion
    ) {
        private CachedPeerOverviewTable {
            if (payload == null) {
                payload = Map.of();
            }
            if (cachedAt == null) {
                cachedAt = Instant.EPOCH;
            }
            if (dataVersion == null) {
                dataVersion = Instant.EPOCH;
            }
        }
    }

    private record PeerLlmAnalysisSnapshot(
            String peerId,
            Map<String, Object> outputPayload,
            List<Map<String, Object>> analysisTrace
    ) {
    }

    private Map<String, Object> mapOf(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            map.put((String) entries[i], entries[i + 1]);
        }
        return map;
    }
}
