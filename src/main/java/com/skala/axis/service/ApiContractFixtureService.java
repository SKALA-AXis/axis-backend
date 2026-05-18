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

    public Map<String, Object> updateNotificationSettings(Map<String, Object> request) {
        Map<String, Object> settings = notificationSettings();
        if (request != null) {
            settings.putAll(request);
        }
        return settings;
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

    public Map<String, Object> mixerOptions() {
        return fixtureMap("mixer_options");
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

    public Map<String, Object> frontendDashboard() {
        return fixtureMap("frontend_dashboard");
    }

    public Map<String, Object> globalSearch(String query) {
        Map<String, Object> result = fixtureMap("global_search");
        result.put("query", query == null || query.isBlank() ? result.get("query") : query);
        return result;
    }

    public Map<String, Object> frontendBriefings() {
        return fixtureMap("frontend_briefings");
    }

    public Map<String, Object> briefingWorkspace(Map<String, String> params) {
        Map<String, Object> workspace = fixtureMap("briefing_workspace");
        String briefingType = params.get("briefing_type");
        if (briefingType != null && !briefingType.isBlank()) {
            workspace.put("briefing_type", briefingType);
        }
        return workspace;
    }

    public Map<String, Object> shareBriefing(String briefingId, Integer expiresInHours) {
        int hours = expiresInHours == null ? 168 : expiresInHours;
        return mapOf(
                "share_url", shareBaseUrl + "/share/briefings/" + briefingId,
                "expires_at", Instant.now().plusSeconds(hours * 3600L).toString()
        );
    }

    public Map<String, Object> frontendAlerts() {
        return fixtureMap("frontend_alerts");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> alertRule(String ruleId, Map<String, Object> request) {
        Map<String, Object> alerts = frontendAlerts();
        List<Map<String, Object>> rules = (List<Map<String, Object>>) alerts.get("rules");
        Map<String, Object> rule = rules.stream()
                .filter(candidate -> ruleId != null && ruleId.equals(candidate.get("id")))
                .findFirst()
                .map(LinkedHashMap::new)
                .orElseGet(() -> new LinkedHashMap<>(rules.get(0)));
        rule.put("id", ruleId == null || ruleId.isBlank() ? "RULE-NEW-001" : ruleId);
        if (request != null) {
            overrideFromRequest(rule, request, "name");
            overrideFromRequest(rule, request, "description");
            overrideFromRequest(rule, request, "enabled");
            overrideFromRequest(rule, request, "channels");
        }
        rule.putIfAbsent("lastTriggered", now());
        return rule;
    }

    public Map<String, Object> frontendPeers() {
        return fixtureMap("frontend_peers");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> peerProfile(String peerId, boolean includeCards) {
        Map<String, Object> peerData = frontendPeers();
        List<Map<String, Object>> peerList = (List<Map<String, Object>>) peerData.get("peers");
        Map<String, Object> selectedPeer = peerList.stream()
                .filter(candidate -> peerId.equals(candidate.get("id")))
                .findFirst()
                .orElseGet(() -> peerList.get(0));
        Map<String, Object> analyses = (Map<String, Object>) peerData.get("analyses");
        Map<String, Object> profileDefaults = fixtureMap("peer_plus_profile_defaults");
        return mapOf(
                "peer", selectedPeer,
                "analysis", analyses.get(String.valueOf(selectedPeer.get("id"))),
                "irProfile", profileDefaults.get("irProfile"),
                "keywordCloud", profileDefaults.get("keywordCloud"),
                "relatedCards", includeCards ? cards(String.valueOf(selectedPeer.get("id"))) : List.of()
        );
    }

    public List<Map<String, Object>> rawArticles() {
        return fixtureList("raw_articles");
    }

    public Map<String, Object> rawArticleList(Map<String, String> params) {
        int limit = intValue(params, "limit", 30);
        int offset = intValue(params, "offset", 0);
        List<Map<String, Object>> items = rawArticles().stream()
                .filter(article -> matches(params.get("peer_id"), article.get("peerId")))
                .filter(article -> matches(params.get("importance_level"), article.get("importanceLevel")))
                .filter(article -> matches(params.get("processing_status"), article.get("processingStatus")))
                .filter(article -> contains(params.get("q"), article.get("title")))
                .skip(offset)
                .limit(limit)
                .toList();
        return mapOf("items", items, "total", rawArticles().size(), "limit", limit, "offset", offset);
    }

    public Map<String, Object> rawArticleDetail(int id) {
        Map<String, Object> article = rawArticles().stream()
                .filter(candidate -> String.valueOf(id).equals(String.valueOf(candidate.get("id"))))
                .findFirst()
                .orElseGet(() -> rawArticles().get(0));
        article.put("content", "AXIS가 수집한 원문 기사 본문 예시입니다. 실제 본문 저장소 연동 전까지 계약 검증용 fixture를 반환합니다.");
        article.put("qualityScore", 0.88);
        article.put("rawTextAvailable", true);
        return article;
    }

    public List<Map<String, Object>> frontendIssues() {
        return fixtureList("frontend_issues");
    }

    public Map<String, Object> issueSummary(String id) {
        Map<String, Object> issue = frontendIssues().stream()
                .filter(candidate -> id.equals(candidate.get("id")))
                .findFirst()
                .orElseGet(() -> frontendIssues().get(0));
        issue.put("relatedCards", cards(String.valueOf(issue.get("peerId"))));
        return issue;
    }

    public Map<String, Object> latestInsight() {
        return fixtureMap("frontend_insight");
    }

    public Map<String, Object> insightGenerationAccepted() {
        return mapOf("job_id", "INSIGHT-JOB-20260511-001", "status", defaultValue("queued_status"));
    }

    public Map<String, Object> keywordGraph() {
        return fixtureMap("keyword_graph");
    }

    public Map<String, Object> keywordGraphCards(String nodeId, Map<String, String> params) {
        return mapOf(
                "nodeId", nodeId,
                "items", cards(null),
                "total", cards(null).size(),
                "limit", intValue(params, "limit", 6),
                "offset", intValue(params, "offset", 0)
        );
    }

    public Map<String, Object> notifications() {
        return fixtureMap("frontend_notifications");
    }

    public Map<String, Object> markNotificationAsRead(String id) {
        return mapOf("id", id, "read", true);
    }

    public Map<String, Object> clearNotifications() {
        return mapOf("cleared", true);
    }

    public Map<String, Object> assistantChat(Map<String, Object> request) {
        Map<String, Object> response = fixtureMap("assistant_chat_response");
        if (request != null && request.get("conversation_id") != null) {
            response.put("conversation_id", request.get("conversation_id"));
        }
        return response;
    }

    public Map<String, Object> viewPreferences() {
        return fixtureMap("view_preferences");
    }

    public Map<String, Object> updateViewPreferences(Map<String, Object> request) {
        Map<String, Object> preferences = viewPreferences();
        if (request != null) {
            preferences.putAll(request);
        }
        return preferences;
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

    private boolean matches(String expected, Object actual) {
        return expected == null || expected.isBlank() || expected.equals(String.valueOf(actual));
    }

    private boolean contains(String query, Object value) {
        return query == null || query.isBlank() || String.valueOf(value).toLowerCase().contains(query.toLowerCase());
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
