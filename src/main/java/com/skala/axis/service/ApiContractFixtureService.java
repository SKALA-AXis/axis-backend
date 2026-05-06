package com.skala.axis.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ApiContractFixtureService {
    private final ObjectMapper objectMapper;
    private final Map<String, Object> fixtures;
    private final String shareBaseUrl;

    public ApiContractFixtureService(
            ObjectMapper objectMapper,
            @Value("${axis.share.base-url}") String shareBaseUrl
    ) {
        this.objectMapper = objectMapper;
        this.shareBaseUrl = trimTrailingSlash(shareBaseUrl);
        this.fixtures = loadFixtures(objectMapper);
    }

    public Map<String, Object> userProfile(Map<String, Object> request) {
        Map<String, Object> profile = fixtureMap("user_profile");
        overrideFromRequest(profile, request, "email");
        overrideFromRequest(profile, request, "name");
        overrideFromRequest(profile, request, "department");
        overrideFromRequest(profile, request, "role");
        return profile;
    }

    public Map<String, Object> login(Map<String, Object> request) {
        return mapOf(
                "access_token", defaultValue("access_token"),
                "refresh_token", defaultValue("refresh_token"),
                "user", userProfile(request)
        );
    }

    public Map<String, Object> verifiedResult() {
        return mapOf("verified", true);
    }

    public Map<String, Object> logoutResult() {
        return result(defaultValue("logout_result"));
    }

    public Map<String, Object> refreshTokenResult() {
        return mapOf("access_token", defaultValue("refreshed_access_token"));
    }

    public Map<String, Object> createdResult() {
        return result(defaultValue("created_result"));
    }

    public Map<String, Object> updatedResult() {
        return result(defaultValue("updated_result"));
    }

    public Map<String, Object> deletedResult(String idKey, String idValue) {
        return mapOf(idKey, idValue, "result", defaultValue("deleted_result"));
    }

    public Map<String, Object> updatedResult(String idKey, String idValue) {
        return mapOf(idKey, idValue, "result", defaultValue("updated_result"));
    }

    public Map<String, Object> changedResult() {
        return result(defaultValue("changed_result"));
    }

    public String defaultCardId() {
        return defaultValue("card_id");
    }

    public String defaultBriefingId() {
        return defaultValue("briefing_id");
    }

    public Map<String, Object> cardList(Map<String, String> filters) {
        int limit = intValue(filters, "limit", 20);
        int offset = intValue(filters, "offset", 0);
        List<Map<String, Object>> items = cards(filters.get("peer_id"));
        return mapOf(
                "items", items,
                "total", items.size(),
                "limit", limit,
                "offset", offset,
                "filters", cardFilters(filters),
                "facets", cardFacets()
        );
    }

    public Map<String, Object> todayCards(Map<String, String> filters) {
        List<Map<String, Object>> items = cards(filters.get("peer_id"));
        return mapOf(
                "date", LocalDate.now().toString(),
                "items", items,
                "total", items.size()
        );
    }

    public Map<String, Object> card(String id) {
        Map<String, Object> card = findCard(id);
        card.put("id", id);
        card.put("is_bookmarked", true);
        return card;
    }

    public Map<String, Object> verifyLinks(String id) {
        return mapOf("source_links", List.of(mapOf(
                "url", shareBaseUrl + "/cards/" + id,
                "status", "ok",
                "archive_url", null
        )));
    }

    public Map<String, Object> shareCard(String id, Integer expiresInHours) {
        int hours = expiresInHours == null ? 24 : expiresInHours;
        return mapOf(
                "share_url", shareBaseUrl + "/share/cards/" + id,
                "expires_at", Instant.now().plusSeconds(hours * 3600L).toString()
        );
    }

    public Map<String, Object> monitoringOverview(Map<String, String> params) {
        String selectedPeerId = params.getOrDefault("peer_id", defaultPeerId());
        Map<String, Object> overview = fixtureMap("monitoring_overview");
        overview.put("period_unit", params.getOrDefault("period_unit", "quarterly"));
        overview.put("period_value", params.getOrDefault("period_value", "2026Q2"));
        overview.put("selected_peer_id", selectedPeerId);
        overview.put("filter_summary", mapOf(
                "peer_count", peers().size(),
                "card_count", cards(null).size(),
                "high_priority_count", 1,
                "updated_at", now()
        ));
        overview.put("peers", peerSummaries());
        overview.put("featured_peer", monitoringPeerDetail(selectedPeerId, params));
        overview.put("recent_cards", cards(params.get("peer_id")));
        return overview;
    }

    public Map<String, Object> monitoringPeers() {
        return mapOf("items", peerSummaries());
    }

    public Map<String, Object> monitoringPeerDetail(String peerId, Map<String, String> params) {
        Map<String, Object> detail = fixtureMap("monitoring_peer_detail");
        detail.put("peer", peer(peerId));
        detail.put("period_unit", params.getOrDefault("period_unit", "quarterly"));
        detail.put("period_value", params.getOrDefault("period_value", "2026Q2"));
        detail.put("headline", peer(peerId).get("name") + " AX 사업 전환 관찰");
        return detail;
    }

    public Map<String, Object> monitoringPeerCards(String peerId) {
        return mapOf("peer", peer(peerId), "items", cards(peerId));
    }

    public Map<String, Object> financials(String peerId, int quarters) {
        Map<String, Object> financials = fixtureMap("financials");
        financials.put("peer_id", peerId);
        return financials;
    }

    public Map<String, Object> monitoringComparison(String metric, int quarters) {
        return mapOf(
                "metric", metric,
                "peers", peers().stream().map(peer -> mapOf(
                        "peer_id", peer.get("id"),
                        "series", fixtureList("comparison_series")
                )).toList()
        );
    }

    public Map<String, Object> peerStrategy(String peerId) {
        Map<String, Object> strategy = fixtureMap("peer_strategy");
        strategy.put("peer_id", peerId);
        return strategy;
    }

    public Map<String, Object> briefing(String id) {
        Map<String, Object> briefing = fixtureMap("briefing");
        briefing.put("id", id);
        return briefing;
    }

    public Map<String, Object> briefingSummaryList() {
        Map<String, Object> report = briefing(defaultBriefingId());
        return mapOf("items", List.of(mapOf(
                "id", report.get("id"),
                "briefing_type", report.get("briefing_type"),
                "title", report.get("title"),
                "date_from", report.get("date_from"),
                "date_to", report.get("date_to"),
                "headline", report.get("executive_summary"),
                "source_card_count", ((List<?>) report.get("source_card_ids")).size(),
                "created_at", report.get("created_at")
        )), "total", 1);
    }

    public Map<String, Object> briefingGenerationAccepted() {
        return mapOf("briefing_id", defaultBriefingId(), "status", defaultValue("queued_status"));
    }

    public Map<String, Object> briefingStatus(String briefingId) {
        return mapOf("briefing_id", briefingId, "status", defaultValue("completed_status"), "progress", 100, "error_message", null);
    }

    public Map<String, Object> alerts() {
        return mapOf("items", List.of(fixtureMap("alert")), "unread_count", 1);
    }

    public Map<String, Object> markAlertAsRead(String id) {
        return mapOf("id", id, "is_read", true);
    }

    public Map<String, Object> alertRules() {
        return fixtureMap("alert_rules");
    }

    public Map<String, Object> notificationSettings() {
        return fixtureMap("notification_settings");
    }

    public Map<String, Object> accessLogs() {
        return mapOf("items", List.of(fixtureMap("access_log")));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> mixerResult(List<String> cardIds) {
        Map<String, Object> result = fixtureMap("mixer_result");
        if (cardIds != null && !cardIds.isEmpty()) {
            result.put("source_card_ids", new ArrayList<>(cardIds));
        }
        return result;
    }

    public Map<String, Object> shareMixerResult(String mixId) {
        return mapOf("share_url", shareBaseUrl + "/share/mixer/" + mixId);
    }

    public List<Map<String, Object>> peers() {
        return fixtureList("peers");
    }

    public List<Map<String, Object>> dataSources() {
        return fixtureList("data_sources");
    }

    public List<Map<String, Object>> prompts() {
        return fixtureList("prompts");
    }

    public List<Map<String, Object>> schedulerJobs() {
        return fixtureList("scheduler_jobs");
    }

    public Map<String, Object> usage(String period) {
        Map<String, Object> usage = fixtureMap("usage");
        usage.put("period", period);
        return usage;
    }

    public Map<String, Object> auditLogs() {
        return mapOf("items", List.of(fixtureMap("audit_log")), "total", 1);
    }

    public Map<String, Object> pipelineStatus() {
        return fixtureMap("pipeline_status");
    }

    public Map<String, Object> pipelineTriggerResult() {
        return mapOf("run_id", defaultValue("pipeline_run_id"), "status", defaultValue("running_status"));
    }

    public static Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            map.put((String) values[i], values[i + 1]);
        }
        return map;
    }

    private Map<String, Object> result(String value) {
        return mapOf("result", value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fixtureMap(String key) {
        Object value = fixtures.get(key);
        Map<String, Object> copy = objectMapper.convertValue(value, new TypeReference<>() {});
        return (Map<String, Object>) resolveTemplates(copy);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fixtureList(String key) {
        Object value = fixtures.get(key);
        List<Map<String, Object>> copy = objectMapper.convertValue(value, new TypeReference<>() {});
        return (List<Map<String, Object>>) resolveTemplates(copy);
    }

    private List<Map<String, Object>> cards(String peerId) {
        List<Map<String, Object>> cards = fixtureList("cards");
        if (peerId == null || peerId.isBlank()) {
            return cards;
        }
        return cards.stream().filter(card -> peerId.equals(card.get("peer_id"))).toList();
    }

    private Map<String, Object> findCard(String id) {
        return cards(null).stream()
                .filter(card -> id.equals(card.get("id")))
                .findFirst()
                .orElseGet(() -> cards(null).get(0));
    }

    private Map<String, Object> cardFilters(Map<String, String> filters) {
        return mapOf(
                "q", filters.get("q"),
                "peer_id", filters.get("peer_id"),
                "sector", filters.get("sector"),
                "exposure_band", filters.get("exposure_band"),
                "event_type", filters.get("event_type"),
                "date_from", filters.get("date_from"),
                "date_to", filters.get("date_to"),
                "source_name", filters.get("source_name"),
                "has_financial_context", filters.get("has_financial_context"),
                "sort", filters.getOrDefault("sort", "latest")
        );
    }

    private Map<String, Object> cardFacets() {
        return fixtureMap("card_facets");
    }

    private List<Map<String, Object>> peerSummaries() {
        return peers().stream().map(peer -> mapOf(
                "peer_id", peer.get("id"),
                "peer_name", peer.get("name"),
                "priority_level", "high",
                "watch_count", 3,
                "review_count", 1,
                "observation_count", 2,
                "status_label", "높음",
                "latest_card_id", defaultCardId()
        )).toList();
    }

    private Map<String, Object> peer(String peerId) {
        return peers().stream()
                .filter(peer -> peerId.equals(peer.get("id")))
                .findFirst()
                .orElseGet(() -> peers().get(0));
    }

    @SuppressWarnings("unchecked")
    private String defaultValue(String key) {
        return String.valueOf(((Map<String, Object>) fixtures.get("defaults")).get(key));
    }

    private String defaultPeerId() {
        return String.valueOf(peers().get(0).get("id"));
    }

    private int intValue(Map<String, String> params, String key, int defaultValue) {
        try {
            return Integer.parseInt(params.getOrDefault(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private void overrideFromRequest(Map<String, Object> target, Map<String, Object> request, String key) {
        if (request != null && request.get(key) != null) {
            target.put(key, request.get(key));
        }
    }

    @SuppressWarnings("unchecked")
    private Object resolveTemplates(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> resolved = new LinkedHashMap<>();
            map.forEach((k, v) -> resolved.put(String.valueOf(k), resolveTemplates(v)));
            return resolved;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::resolveTemplates).toList();
        }
        if (value instanceof String text) {
            return text
                    .replace("{{now}}", now())
                    .replace("{{today}}", LocalDate.now().toString())
                    .replace("{{month}}", LocalDate.now().withDayOfMonth(1).toString().substring(0, 7));
        }
        return value;
    }

    private String now() {
        return Instant.now().toString();
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private Map<String, Object> loadFixtures(ObjectMapper mapper) {
        try {
            return mapper.readValue(
                    new ClassPathResource("contract-fixtures.json").getInputStream(),
                    new TypeReference<>() {}
            );
        } catch (IOException e) {
            throw new IllegalStateException("contract-fixtures.json 로드 실패", e);
        }
    }
}
