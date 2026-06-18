/*
 * 작성일: 2026-06-08
 * 작성자: 박진
 * 변경이력:
 *   2026-06-08 박진 — assistant 대화 API 추가, PDF 챗 지원·챗봇 로직 수정·AI 연동 강화 및 목업 삭제·챗봇 고도화
 *   2026-06-11 안가은 — 백엔드 SQL 쿼리 분리
 *   2026-06-17 최종민 — 쓰기 보유 JDBC 서비스 메서드 레벨 @Transactional 적용
 */
package com.skala.axis.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skala.axis.config.AuthPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.skala.axis.query.AssistantConversationQueries.COUNT_ACCESS_BY_DEVICE;
import static com.skala.axis.query.AssistantConversationQueries.COUNT_ACCESS_BY_USER;
import static com.skala.axis.query.AssistantConversationQueries.DELETE_CONVERSATION_IF_DELETED;
import static com.skala.axis.query.AssistantConversationQueries.DELETE_MESSAGES_BY_CONVERSATION;
import static com.skala.axis.query.AssistantConversationQueries.END_CONVERSATION;
import static com.skala.axis.query.AssistantConversationQueries.INSERT_ASSISTANT_MESSAGE;
import static com.skala.axis.query.AssistantConversationQueries.INSERT_USER_MESSAGE;
import static com.skala.axis.query.AssistantConversationQueries.LIST_BY_DEVICE_HASH;
import static com.skala.axis.query.AssistantConversationQueries.LIST_BY_USER;
import static com.skala.axis.query.AssistantConversationQueries.MESSAGE_DETAIL_BY_CONVERSATION;
import static com.skala.axis.query.AssistantConversationQueries.RECENT_HISTORY;
import static com.skala.axis.query.AssistantConversationQueries.SOFT_DELETE_BY_DEVICE;
import static com.skala.axis.query.AssistantConversationQueries.SOFT_DELETE_BY_USER;
import static com.skala.axis.query.AssistantConversationQueries.SOFT_DELETE_BY_USER_OR_DEVICE;
import static com.skala.axis.query.AssistantConversationQueries.UPSERT_CONVERSATION;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AssistantConversationService {
    private static final int DEFAULT_HISTORY_LIMIT = 12;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Object>> LIST_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Transactional
    public Map<String, Object> prepareChatRequest(Map<String, Object> request, Authentication authentication) {
        Map<String, Object> body = mutableCopy(request);
        UUID conversationId = resolveConversationId(body);
        body.put("conversation_id", conversationId.toString());
        body.put("session_id", conversationId.toString());

        UUID userId = currentUserId(authentication);
        String deviceHash = deviceHash(body, conversationId);
        String message = stringValue(body.get("message"));
        if (!canUseConversation(conversationId, userId, deviceHash)) {
            log.warn("assistant conversation ownership mismatch | requested={} user_id_present={}",
                    conversationId, userId != null);
            conversationId = UUID.randomUUID();
            body.put("conversation_id", conversationId.toString());
            body.put("session_id", conversationId.toString());
            deviceHash = deviceHash(body, conversationId);
        }

        try {
            ensureConversation(conversationId, userId, deviceHash, message, body);
            if (isEmptyHistory(body.get("history"))) {
                body.put("history", recentHistory(conversationId, DEFAULT_HISTORY_LIMIT));
            }
            insertUserMessage(conversationId, message, body);
        } catch (Exception e) {
            log.debug("assistant conversation prepare skipped | conversation={} error={}",
                    conversationId, e.getMessage());
        }
        return body;
    }

    @Transactional
    public Map<String, Object> completeChatTurn(Map<String, Object> request, Map<String, Object> response) {
        Map<String, Object> normalized = normalizeAssistantResponse(response);
        UUID conversationId = parseUuid(
                stringValue(normalized.get("conversation_id")),
                parseUuid(stringValue(request.get("conversation_id")), null)
        );
        if (conversationId == null) {
            return normalized;
        }
        try {
            insertAssistantMessage(conversationId, normalized);
        } catch (Exception e) {
            log.debug("assistant response persistence skipped | conversation={} error={}",
                    conversationId, e.getMessage());
        }
        return normalized;
    }

    public Map<String, Object> normalizeAssistantResponse(Map<String, Object> response) {
        Map<String, Object> out = mutableCopy(response);
        String reply = stringValue(out.get("reply"));
        Object message = out.get("message");
        if (reply.isBlank() && message instanceof Map<?, ?> messageMap) {
            reply = stringValue(messageMap.get("content"));
            if (!reply.isBlank()) {
                out.put("reply", reply);
            }
        }
        if (!(message instanceof Map<?, ?>)) {
            Map<String, Object> messagePayload = new LinkedHashMap<>();
            messagePayload.put("role", "assistant");
            messagePayload.put("content", reply);
            messagePayload.put("reportPreview", out.containsKey("report_draft"));
            out.put("message", messagePayload);
        }
        out.putIfAbsent("sources", List.of());
        out.putIfAbsent("answer_blocks", List.of());
        out.putIfAbsent("follow_up_suggestions", out.getOrDefault("suggested_actions", List.of()));
        return out;
    }

    @Transactional
    public Map<String, Object> createConversation(Map<String, Object> request, Authentication authentication) {
        Map<String, Object> body = mutableCopy(request);
        UUID conversationId = resolveConversationId(body);
        body.put("conversation_id", conversationId.toString());
        UUID userId = currentUserId(authentication);
        String deviceHash = deviceHash(body, conversationId);
        try {
            ensureConversation(conversationId, userId, deviceHash, stringValue(body.get("title")), body);
        } catch (Exception e) {
            log.debug("assistant conversation create skipped | conversation={} error={}",
                    conversationId, e.getMessage());
        }
        return Map.of(
                "conversation_id", conversationId.toString(),
                "session_id", conversationId.toString(),
                "status", "active"
        );
    }

    public List<Map<String, Object>> listConversations(
            Authentication authentication,
            String deviceId,
            int limit
    ) {
        UUID userId = currentUserId(authentication);
        String deviceHash = hashNullable(deviceId);
        if (userId == null && deviceHash == null) {
            return List.of();
        }
        int safeLimit = Math.max(1, Math.min(limit, 50));
        try {
            if (userId != null) {
                return jdbcTemplate.queryForList(LIST_BY_USER, userId, safeLimit);
            }
            return jdbcTemplate.queryForList(LIST_BY_DEVICE_HASH, deviceHash, safeLimit);
        } catch (Exception e) {
            log.debug("assistant conversation list skipped | error={}", e.getMessage());
            return List.of();
        }
    }

    public Map<String, Object> conversationDetail(
            String conversationId,
            Authentication authentication,
            String deviceId
    ) {
        UUID id = parseUuid(conversationId, null);
        if (id == null || !canAccess(id, authentication, deviceId)) {
            return Map.of("conversation_id", conversationId, "messages", List.of());
        }
        try {
            List<Map<String, Object>> messages = jdbcTemplate.queryForList(MESSAGE_DETAIL_BY_CONVERSATION, id)
                    .stream()
                    .map(this::normalizeMessageRow)
                    .toList();
            return Map.of("conversation_id", id.toString(), "messages", messages);
        } catch (Exception e) {
            log.debug("assistant conversation detail skipped | conversation={} error={}",
                    conversationId, e.getMessage());
            return Map.of("conversation_id", conversationId, "messages", List.of());
        }
    }

    @Transactional
    public Map<String, Object> endConversation(
            String conversationId,
            Authentication authentication,
            String deviceId
    ) {
        UUID id = parseUuid(conversationId, null);
        if (id == null || !canAccess(id, authentication, deviceId)) {
            return Map.of("conversation_id", conversationId, "status", "not_found");
        }
        try {
            jdbcTemplate.update(END_CONVERSATION, id);
        } catch (Exception e) {
            log.debug("assistant conversation end skipped | conversation={} error={}",
                    conversationId, e.getMessage());
        }
        return Map.of("conversation_id", id.toString(), "status", "ended");
    }

    @Transactional
    public Map<String, Object> deleteConversation(
            String conversationId,
            Authentication authentication,
            String deviceId
    ) {
        UUID id = parseUuid(conversationId, null);
        if (id == null) {
            return Map.of(
                    "conversation_id", conversationId,
                    "status", "invalid_id",
                    "deleted", false,
                    "error_code", "ASSISTANT_CONVERSATION_INVALID_ID"
            );
        }
        UUID userId = currentUserId(authentication);
        String deviceHash = hashNullable(deviceId);
        if (userId == null && deviceHash == null) {
            return Map.of(
                    "conversation_id", conversationId,
                    "status", "not_found",
                    "deleted", false,
                    "error_code", "ASSISTANT_CONVERSATION_OWNER_REQUIRED"
            );
        }
        try {
            int deletedRows = softDeleteConversationRow(id, userId, deviceHash);
            if (deletedRows <= 0) {
                return Map.of(
                        "conversation_id", id.toString(),
                        "status", "not_found",
                        "deleted", false,
                        "error_code", "ASSISTANT_CONVERSATION_NOT_FOUND"
                );
            }
            purgeDeletedConversation(id);
        } catch (Exception e) {
            log.warn("assistant conversation delete failed | conversation={} error={}",
                    conversationId, e.getMessage());
            return Map.of(
                    "conversation_id", id.toString(),
                    "status", "failed",
                    "deleted", false,
                    "error_code", "ASSISTANT_CONVERSATION_DELETE_FAILED"
            );
        }
        return Map.of(
                "conversation_id", id.toString(),
                "status", "deleted",
                "deleted", true,
                "delete_mode", "soft"
        );
    }

    private int softDeleteConversationRow(UUID conversationId, UUID userId, String deviceHash) {
        if (userId != null && deviceHash != null) {
            return jdbcTemplate.update(SOFT_DELETE_BY_USER_OR_DEVICE, conversationId, userId, deviceHash);
        }
        if (userId != null) {
            return jdbcTemplate.update(SOFT_DELETE_BY_USER, conversationId, userId);
        }
        return jdbcTemplate.update(SOFT_DELETE_BY_DEVICE, conversationId, deviceHash);
    }

    private void purgeDeletedConversation(UUID conversationId) {
        try {
            jdbcTemplate.update(DELETE_MESSAGES_BY_CONVERSATION, conversationId);
            jdbcTemplate.update(DELETE_CONVERSATION_IF_DELETED, conversationId);
        } catch (Exception e) {
            log.debug("assistant conversation purge skipped | conversation={} error={}",
                    conversationId, e.getMessage());
        }
    }

    private void ensureConversation(
            UUID conversationId,
            UUID userId,
            String deviceHash,
            String titleSeed,
            Map<String, Object> metadata
    ) throws Exception {
        String title = titleSeed == null || titleSeed.isBlank()
                ? "새 대화"
                : titleSeed.strip().substring(0, Math.min(titleSeed.strip().length(), 80));
        jdbcTemplate.update(UPSERT_CONVERSATION, conversationId, userId, deviceHash, title, toJson(metadata));
    }

    private void insertUserMessage(UUID conversationId, String message, Map<String, Object> request)
            throws Exception {
        if (message == null || message.isBlank()) {
            return;
        }
        jdbcTemplate.update(INSERT_USER_MESSAGE, conversationId, message, toJson(request), conversationId);
    }

    private void insertAssistantMessage(UUID conversationId, Map<String, Object> response)
            throws Exception {
        Map<String, Object> provenance = mapValue(response.get("provenance"));
        Map<String, Object> safety = new LinkedHashMap<>();
        safety.put("blocked", response.getOrDefault("blocked", false));
        safety.put("blocked_reason", response.get("blocked_reason"));
        jdbcTemplate.update(INSERT_ASSISTANT_MESSAGE,
                conversationId,
                stringValue(response.get("reply")),
                stringValue(response.get("intent")),
                stringValue(response.get("scope")),
                toJson(response),
                toJson(response.getOrDefault("sources", List.of())),
                toJson(provenance),
                toJson(Map.of()),
                toJson(safety),
                doubleOrNull(response.get("confidence")),
                stringValue(provenance.get("langfuse_trace_id")),
                conversationId
        );
    }

    private List<Map<String, Object>> recentHistory(UUID conversationId, int limit) {
        try {
            return jdbcTemplate.queryForList(RECENT_HISTORY, conversationId, limit).stream()
                    .map(row -> Map.of(
                            "role", row.get("role"),
                            "content", row.get("content")
                    ))
                    .toList();
        } catch (Exception e) {
            log.debug("assistant history load skipped | conversation={} error={}",
                    conversationId, e.getMessage());
            return List.of();
        }
    }

    private boolean canAccess(UUID conversationId, Authentication authentication, String deviceId) {
        UUID userId = currentUserId(authentication);
        String deviceHash = hashNullable(deviceId);
        try {
            Integer count;
            if (userId != null) {
                count = jdbcTemplate.queryForObject(COUNT_ACCESS_BY_USER, Integer.class, conversationId, userId);
            } else if (deviceHash != null) {
                count = jdbcTemplate.queryForObject(COUNT_ACCESS_BY_DEVICE, Integer.class, conversationId, deviceHash);
            } else {
                return false;
            }
            return count != null && count > 0;
        } catch (Exception e) {
            log.debug("assistant access check skipped | conversation={} error={}",
                    conversationId, e.getMessage());
            return false;
        }
    }

    private boolean canUseConversation(UUID conversationId, UUID userId, String deviceHash) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT user_id::text AS user_id,
                           device_id_hash,
                           status
                      FROM assistant_conversations
                     WHERE id = ?
                     LIMIT 1
                    """, conversationId);
            if (rows.isEmpty()) {
                return true;
            }

            Map<String, Object> row = rows.get(0);
            if ("deleted".equals(stringValue(row.get("status")))) {
                return false;
            }

            UUID ownerUserId = parseUuid(stringValue(row.get("user_id")), null);
            String ownerDeviceHash = stringValue(row.get("device_id_hash"));
            if (userId != null && ownerUserId != null) {
                return userId.equals(ownerUserId);
            }
            return deviceHash != null && !deviceHash.isBlank() && deviceHash.equals(ownerDeviceHash);
        } catch (Exception e) {
            log.debug("assistant ownership precheck skipped | conversation={} error={}",
                    conversationId, e.getMessage());
            return true;
        }
    }

    private Map<String, Object> normalizeMessageRow(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>(row);
        for (String key : List.of("answer_payload", "retrieval_trace", "safety")) {
            out.put(key, parseJsonObject(out.get(key)));
        }
        out.put("sources", parseJsonList(out.get("sources")));
        return out;
    }

    private UUID resolveConversationId(Map<String, Object> body) {
        UUID fromConversation = parseUuid(stringValue(body.get("conversation_id")), null);
        if (fromConversation != null) {
            return fromConversation;
        }
        UUID fromSession = parseUuid(stringValue(body.get("session_id")), null);
        return fromSession != null ? fromSession : UUID.randomUUID();
    }

    private UUID parseUuid(String value, UUID fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private UUID currentUserId(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof AuthPrincipal principal) {
            return principal.userId();
        }
        return null;
    }

    private String deviceHash(Map<String, Object> body, UUID conversationId) {
        String deviceId = stringValue(body.get("device_id"));
        if (deviceId.isBlank()) {
            Map<String, Object> clientContext = mapValue(body.get("client_context"));
            deviceId = stringValue(clientContext.get("device_id"));
        }
        if (deviceId.isBlank()) {
            deviceId = "anonymous:" + conversationId;
        }
        return sha256(deviceId);
    }

    private String hashNullable(String value) {
        String cleaned = stringValue(value);
        return cleaned.isBlank() ? null : sha256(cleaned);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encoded = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte item : encoded) {
                out.append(String.format("%02x", item));
            }
            return out.toString();
        } catch (Exception e) {
            return value;
        }
    }

    private Map<String, Object> mutableCopy(Map<String, Object> input) {
        return input == null ? new LinkedHashMap<>() : new LinkedHashMap<>(input);
    }

    private boolean isEmptyHistory(Object value) {
        return !(value instanceof List<?> list) || list.isEmpty();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Double doubleOrNull(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            String text = stringValue(value);
            return text.isBlank() ? null : Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> out = new LinkedHashMap<>();
            raw.forEach((key, item) -> out.put(String.valueOf(key), item));
            return out;
        }
        return new LinkedHashMap<>();
    }

    private String toJson(Object value) throws Exception {
        return objectMapper.writeValueAsString(value == null ? Map.of() : value);
    }

    private Map<String, Object> parseJsonObject(Object value) {
        if (value instanceof Map<?, ?> raw) {
            return mapValue(raw);
        }
        String text = stringValue(value);
        if (text.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(text, MAP_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private List<Object> parseJsonList(Object value) {
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        String text = stringValue(value);
        if (text.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(text, LIST_TYPE);
        } catch (Exception e) {
            return List.of();
        }
    }
}
