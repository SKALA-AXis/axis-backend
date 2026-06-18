/*
 * 작성일: 2026-06-16
 * 작성자: 심유정
 * 변경이력:
 *   2026-06-16 심유정 — 사용자 전략 컨텍스트 설정 API 신설, 이후 카드 전략 액션 projection 추가
 */
package com.skala.axis.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skala.axis.domain.User;
import com.skala.axis.exception.AuthException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAccessor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.skala.axis.query.UserStrategyContextQueries.COUNT_CONTEXTS_BY_USER_SQL;
import static com.skala.axis.query.UserStrategyContextQueries.DEACTIVATE_STRATEGY_PROJECTIONS_SQL;
import static com.skala.axis.query.UserStrategyContextQueries.DELETE_CONTEXT_SQL;
import static com.skala.axis.query.UserStrategyContextQueries.INSERT_CONTEXT_SQL;
import static com.skala.axis.query.UserStrategyContextQueries.LIST_BY_USER_SQL;
import static com.skala.axis.query.UserStrategyContextQueries.UPDATE_CONTEXT_SQL;

@Service
@RequiredArgsConstructor
public class UserStrategyContextService {
    private static final int MAX_TEXT_CHARS = 80_000;
    private static final long MAX_FILE_BYTES = 5L * 1024L * 1024L;

    private final AuthService authService;
    private final AiClientService aiClientService;
    private final UserStrategyFileExtractionService fileExtractionService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public Map<String, Object> list(UUID userId) {
        authService.requireUser(userId);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                LIST_BY_USER_SQL,
                userId
        );
        return Map.of("items", rows.stream().map(this::toItem).toList());
    }

    public Map<String, Object> create(UUID userId, Map<String, Object> request, RequestMetadata metadata) {
        User user = authService.requireUser(userId);
        NormalizedContext context = normalizeContext(request);
        Map<String, Object> overlay = generateOverlay(userId, context);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                INSERT_CONTEXT_SQL,
                userId,
                context.rawText(),
                toJson(overlay),
                context.sourceType(),
                context.fileName(),
                context.fileSize(),
                toJson(context.metadata())
        );
        authService.recordAccessLog(
                user,
                "SETTINGS_UPDATED",
                true,
                metadata,
                null,
                Map.of("target", "strategy_contexts", "operation", "create")
        );
        return toItem(row);
    }

    public Map<String, Object> update(
            UUID userId,
            UUID contextId,
            Map<String, Object> request,
            RequestMetadata metadata
    ) {
        User user = authService.requireUser(userId);
        NormalizedContext context = normalizeContext(request);
        Map<String, Object> overlay = generateOverlay(userId, context);
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    UPDATE_CONTEXT_SQL,
                    context.rawText(),
                    toJson(overlay),
                    context.sourceType(),
                    context.fileName(),
                    context.fileSize(),
                    toJson(context.metadata()),
                    contextId,
                    userId
            );
            authService.recordAccessLog(
                    user,
                    "SETTINGS_UPDATED",
                    true,
                    metadata,
                    null,
                    Map.of("target", "strategy_contexts", "operation", "update")
            );
            return toItem(row);
        } catch (EmptyResultDataAccessException exc) {
            throw new AuthException(HttpStatus.NOT_FOUND, "STRATEGY_CONTEXT_NOT_FOUND", "전략 자료를 찾을 수 없습니다.");
        }
    }

    public Map<String, Object> delete(UUID userId, UUID contextId, RequestMetadata metadata) {
        User user = authService.requireUser(userId);
        int deleted = jdbcTemplate.update(
                DELETE_CONTEXT_SQL,
                contextId,
                userId
        );
        if (deleted == 0) {
            throw new AuthException(HttpStatus.NOT_FOUND, "STRATEGY_CONTEXT_NOT_FOUND", "전략 자료를 찾을 수 없습니다.");
        }
        deactivateStrategyProjectionsWhenNoContextRemains(userId);
        authService.recordAccessLog(
                user,
                "SETTINGS_UPDATED",
                true,
                metadata,
                null,
                Map.of("target", "strategy_contexts", "operation", "delete")
        );
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", contextId.toString());
        response.put("deleted", true);
        return response;
    }

    private void deactivateStrategyProjectionsWhenNoContextRemains(UUID userId) {
        Integer remaining = jdbcTemplate.queryForObject(
                COUNT_CONTEXTS_BY_USER_SQL,
                Integer.class,
                userId
        );
        if (remaining != null && remaining > 0) {
            return;
        }
        jdbcTemplate.update(
                DEACTIVATE_STRATEGY_PROJECTIONS_SQL,
                OffsetDateTime.now(ZoneOffset.UTC).toString(),
                userId
        );
    }

    public Map<String, Object> extractFile(MultipartFile file) {
        return fileExtractionService.extract(file);
    }

    private NormalizedContext normalizeContext(Map<String, Object> request) {
        String rawText = firstString(request, "rawText", "raw_text", "content").trim();
        if (rawText.isBlank()) {
            throw new IllegalArgumentException("전략 자료 내용을 입력하세요.");
        }
        if (rawText.length() > MAX_TEXT_CHARS) {
            throw new IllegalArgumentException("전략 자료는 80,000자 이내로 입력하세요.");
        }

        String fileName = blankToNull(firstString(request, "fileName", "file_name"));
        Long fileSize = longValue(request == null ? null : request.get("fileSize"));
        if (fileSize == null) {
            fileSize = longValue(request == null ? null : request.get("file_size"));
        }
        if (fileSize != null && fileSize > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("전략 자료 파일은 5MB 이내로 업로드하세요.");
        }

        String sourceType = normalizeSourceType(firstString(request, "sourceType", "source_type"), fileName);
        Map<String, Object> metadata = mapValue(request == null ? null : request.get("metadata"));
        metadata.put("rawTextLength", rawText.length());
        if (fileName != null) {
            metadata.put("fileName", fileName);
        }
        if (fileSize != null) {
            metadata.put("fileSize", fileSize);
        }
        return new NormalizedContext(rawText, sourceType, fileName, fileSize, metadata);
    }

    private Map<String, Object> generateOverlay(UUID userId, NormalizedContext context) {
        Map<String, Object> metadata = new LinkedHashMap<>(context.metadata());
        metadata.put("sourceType", context.sourceType());
        Map<String, Object> response = aiClientService.summarizeUserStrategyOverlay(
                context.rawText(),
                null,
                userId,
                metadata
        ).block();
        Object overlay = response == null ? null : response.get("overlay");
        if (!(overlay instanceof Map<?, ?> overlayMap)) {
            overlay = response;
            if (!(overlay instanceof Map<?, ?> overlayMapFallback)) {
                throw new IllegalArgumentException("전략 자료 구조화에 실패했습니다.");
            }
            return stringKeyMap(overlayMapFallback);
        }
        Map<String, Object> result = stringKeyMap(overlayMap);
        if (result.isEmpty()) {
            throw new IllegalArgumentException("전략 자료 구조화에 실패했습니다.");
        }
        return result;
    }

    private Map<String, Object> toItem(Map<String, Object> row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", stringValue(row.get("id")));
        item.put("rawText", stringValue(row.get("raw_text")));
        item.put("content", stringValue(row.get("raw_text")));
        item.put("overlayJson", jsonValue(row.get("overlay_json")));
        item.put("sourceType", stringValue(row.get("source_type")));
        item.put("fileName", nullableString(row.get("file_name")));
        item.put("fileSize", row.get("file_size"));
        item.put("metadata", jsonValue(row.get("metadata")));
        item.put("createdAt", isoValue(row.get("created_at")));
        item.put("updatedAt", isoValue(row.get("updated_at")));
        return item;
    }

    private String normalizeSourceType(String raw, String fileName) {
        String value = raw == null ? "" : raw.trim().toLowerCase();
        if (value.equals("uploaded_file") || value.equals("file")) {
            return "uploaded_file";
        }
        if (value.equals("manual_text") || value.equals("manual")) {
            return "manual_text";
        }
        return fileName == null ? "manual_text" : "uploaded_file";
    }

    private String firstString(Map<String, Object> source, String... keys) {
        if (source == null) {
            return "";
        }
        for (String key : keys) {
            Object value = source.get(key);
            if (value instanceof String string) {
                return string;
            }
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return "";
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String string && !string.isBlank()) {
            try {
                return Long.parseLong(string.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return stringKeyMap(map);
        }
        return new LinkedHashMap<>();
    }

    private Map<String, Object> stringKeyMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private Object jsonValue(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            return stringKeyMap(map);
        }
        if (value instanceof List<?>) {
            return value;
        }
        String text = String.valueOf(value);
        if (!text.startsWith("{") && !text.startsWith("[")) {
            return value;
        }
        try {
            return objectMapper.readValue(text, Object.class);
        } catch (JsonProcessingException ignored) {
            return value;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exc) {
            throw new IllegalArgumentException("전략 자료 JSON 변환에 실패했습니다.");
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String nullableString(Object value) {
        String text = stringValue(value);
        return text.isBlank() ? null : text;
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String isoValue(Object value) {
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant().toString();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant().toString();
        }
        if (value instanceof TemporalAccessor) {
            return String.valueOf(value);
        }
        return value == null ? "" : String.valueOf(value);
    }

    private record NormalizedContext(
            String rawText,
            String sourceType,
            String fileName,
            Long fileSize,
            Map<String, Object> metadata
    ) {}
}
