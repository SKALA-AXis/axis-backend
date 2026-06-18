/*
 * 작성일: 2026-06-10
 * 작성자: 박진
 * 변경이력:
 *   2026-06-10 박진 — mixer 결과 영속화 및 최신 인사이트 제공, 목업 삭제·챗봇 고도화
 *   2026-06-17 최종민 — 쓰기 보유 JDBC 서비스 메서드 레벨 @Transactional 적용
 */
package com.skala.axis.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.skala.axis.query.MixerResultQueries.RECENT_MIXER_RESULTS_SQL;
import static com.skala.axis.query.MixerResultQueries.SHARE_PAYLOAD_SQL;
import static com.skala.axis.query.MixerResultQueries.UPSERT_MIXER_RESULT_SQL;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MixerResultService {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Object>> LIST_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Transactional
    public void saveStreamEvent(
            String rawEvent,
            List<String> cardIds,
            Map<String, Object> ratios,
            String userContext,
            String analysisMode) {
        Map<String, Object> event = parseObject(stripSsePrefix(rawEvent));
        if (!"result".equals(String.valueOf(event.get("type")))) {
            return;
        }
        Object data = event.get("data");
        if (data instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) map;
            saveResult(result, cardIds, ratios, userContext, analysisMode);
        }
    }

    @Transactional
    public void saveResult(
            Map<String, Object> result,
            List<String> cardIds,
            Map<String, Object> ratios,
            String userContext,
            String analysisMode) {
        if (result == null || result.isEmpty() || "failed".equals(result.get("status"))) {
            return;
        }
        String sourceAnalysisId = stringValue(result.get("mix_id"));
        if (sourceAnalysisId.isBlank()) {
            sourceAnalysisId = "mixer-" + System.currentTimeMillis();
        }
        String savedSourceAnalysisId = sourceAnalysisId;
        List<String> inputCardIds = nonBlankList(cardIds);
        List<String> peerIds = nonBlankList(result.get("peer_ids"));
        List<String> keywords = keywordsFromRatios(ratios);
        Map<String, Object> payload = new LinkedHashMap<>(result);
        payload.put("request", Map.of(
                "card_ids", inputCardIds,
                "ratios", ratios == null ? Map.of() : ratios,
                "user_context", userContext == null ? "" : userContext,
                "analysis_mode", normalizeMode(analysisMode)
        ));

        try {
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(UPSERT_MIXER_RESULT_SQL);
                ps.setString(1, savedSourceAnalysisId);
                ps.setString(2, firstText(result, "mix_insight", "insight", "final_one_liner"));
                Array cardArray = connection.createArrayOf("text", inputCardIds.toArray(String[]::new));
                Array peerArray = connection.createArrayOf("text", peerIds.toArray(String[]::new));
                Array keywordArray = connection.createArrayOf("text", keywords.toArray(String[]::new));
                ps.setArray(3, cardArray);
                ps.setArray(4, peerArray);
                ps.setArray(5, keywordArray);
                ps.setString(6, toJson(ratios == null ? Map.of() : ratios));
                ps.setString(7, toJson(generatedImplication(result)));
                ps.setString(8, toJson(insightBrief(result)));
                ps.setString(9, toJson(listValue(result.get("radar_axes"))));
                ps.setString(10, toJson(listValue(result.get("connections"))));
                ps.setString(11, stringOrNull(result.get("sk_ax_implication")));
                ps.setString(12, stringOrNull(result.get("final_one_liner")));
                ps.setObject(13, confidenceValue(result.get("confidence")));
                ps.setString(14, toJson(payload));
                return ps;
            });
        } catch (DataAccessException e) {
            log.warn("mixer result 저장 실패 | mix_id={} error={}", sourceAnalysisId, e.getMessage());
        }
    }

    public List<Map<String, Object>> recent(int rawLimit) {
        int limit = Math.max(1, Math.min(rawLimit, 20));
        try {
            return jdbcTemplate.query(
                    RECENT_MIXER_RESULTS_SQL,
                    (rs, rowNum) -> {
                        Map<String, Object> payload = parseObject(rs.getString("payload_json"));
                        Map<String, Object> provenance = mapValue(payload.get("provenance"));
                        return mapOf(
                                "id", rs.getString("id"),
                                "mix_id", rs.getString("source_analysis_id"),
                                "title", rs.getString("title"),
                                "final_one_liner", rs.getString("final_one_liner"),
                                "sk_ax_implication", rs.getString("sk_ax_implication"),
                                "confidence", rs.getObject("confidence"),
                                "peer_ids", parseList(rs.getString("peer_ids_json")),
                                "input_card_ids", parseList(rs.getString("card_ids_json")),
                                "input_keywords", parseList(rs.getString("keywords_json")),
                                "analysis_mode", provenance.getOrDefault("analysis_mode", ""),
                                "payload", payload,
                                "created_at", rs.getString("created_at"),
                                "updated_at", rs.getString("updated_at")
                        );
                    },
                    limit
            );
        } catch (DataAccessException e) {
            log.warn("mixer recent 조회 실패 | error={}", e.getMessage());
            return List.of();
        }
    }

    public Map<String, Object> sharePayload(String rawMixId, String shareBaseUrl) {
        String mixId = stringValue(rawMixId);
        if (mixId.isBlank()) {
            return mapOf(
                    "status", "failed",
                    "result_kind", "mixer_share_invalid_mix_id",
                    "mix_id", ""
            );
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.query(
                    SHARE_PAYLOAD_SQL,
                    (rs, rowNum) -> {
                        String resolvedMixId = stringValue(rs.getString("source_analysis_id"));
                        if (resolvedMixId.isBlank()) {
                            resolvedMixId = rs.getString("id");
                        }
                        Map<String, Object> payload = parseObject(rs.getString("payload_json"));
                        return mapOf(
                                "status", "ready",
                                "result_kind", "mixer_share_payload",
                                "mix_id", resolvedMixId,
                                "id", rs.getString("id"),
                                "title", rs.getString("title"),
                                "final_one_liner", rs.getString("final_one_liner"),
                                "sk_ax_implication", rs.getString("sk_ax_implication"),
                                "confidence", rs.getObject("confidence"),
                                "input_card_ids", parseList(rs.getString("card_ids_json")),
                                "peer_ids", parseList(rs.getString("peer_ids_json")),
                                "input_keywords", parseList(rs.getString("keywords_json")),
                                "payload", payload,
                                "share_url", shareUrl(shareBaseUrl, resolvedMixId),
                                "created_at", rs.getString("created_at"),
                                "updated_at", rs.getString("updated_at")
                        );
                    },
                    mixId,
                    mixId
            );
            if (rows.isEmpty()) {
                return mapOf(
                        "status", "not_found",
                        "result_kind", "mixer_share_result_not_found",
                        "mix_id", mixId
                );
            }
            return rows.get(0);
        } catch (DataAccessException e) {
            log.warn("mixer share payload 조회 실패 | mix_id={} error={}", mixId, e.getMessage());
            return mapOf(
                    "status", "failed",
                    "result_kind", "mixer_share_store_unavailable",
                    "mix_id", mixId
            );
        }
    }

    private Map<String, Object> generatedImplication(Map<String, Object> result) {
        return mapOf(
                "sk_ax_implication", result.get("sk_ax_implication"),
                "recommended_actions", listValue(result.get("recommended_actions")),
                "action_details", listValue(result.get("action_details")),
                "follow_up_checks", listValue(result.get("follow_up_checks"))
        );
    }

    private Map<String, Object> insightBrief(Map<String, Object> result) {
        return mapOf(
                "bullet_signals", listValue(result.get("bullet_signals")),
                "cross_card_findings", listValue(result.get("cross_card_findings")),
                "reasoning_trail", listValue(result.get("reasoning_trail"))
        );
    }

    private List<String> keywordsFromRatios(Map<String, Object> ratios) {
        if (ratios == null) {
            return List.of();
        }
        Object keyword = ratios.get("keyword");
        if (keyword instanceof Map<?, ?> map) {
            return map.keySet().stream().map(String::valueOf).filter(s -> !s.isBlank()).toList();
        }
        return nonBlankList(keyword);
    }

    private List<String> nonBlankList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(String::valueOf)
                    .map(String::trim)
                    .filter(item -> !item.isBlank())
                    .distinct()
                    .toList();
        }
        return List.of();
    }

    private String stripSsePrefix(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.startsWith("data:")) {
            return value.substring(5).trim();
        }
        return value;
    }

    private Map<String, Object> parseObject(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private List<Object> parseList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, LIST_TYPE);
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<Object> listValue(Object value) {
        return value instanceof List<?> list ? new ArrayList<>(list) : List.of();
    }

    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, mapValue) -> result.put(String.valueOf(key), mapValue));
            return result;
        }
        return Map.of();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private String firstText(Map<String, Object> result, String... keys) {
        for (String key : keys) {
            String value = stringValue(result.get(key));
            if (!value.isBlank()) {
                return value;
            }
        }
        return "믹서 결과";
    }

    private String stringOrNull(Object value) {
        String text = stringValue(value);
        return text.isBlank() ? null : text;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Double confidenceValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? null : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String normalizeMode(String value) {
        return "deep".equals(String.valueOf(value).trim().toLowerCase(Locale.ROOT)) ? "deep" : "quick";
    }

    private String shareUrl(String baseUrl, String mixId) {
        String base = stringValue(baseUrl);
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.isBlank()) {
            base = "https://axis.local";
        }
        return base + "/mixer/shared/" + mixId;
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            result.put(String.valueOf(values[i]), values[i + 1]);
        }
        return result;
    }
}
