package com.skala.axis.service;

import com.skala.axis.domain.CardNews;
import com.skala.axis.domain.CardNewsStatus;
import com.skala.axis.domain.User;
import com.skala.axis.domain.UserNotification;
import com.skala.axis.repository.CardNewsRepository;
import com.skala.axis.repository.UserNotificationRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserNotificationService {
    private final AuthService authService;
    private final CardNewsRepository cardNewsRepository;
    private final UserNotificationRepository notificationRepository;
    @Value("${axis.notification.important-keywords:}")
    private List<String> importantKeywords;
    @Value("${axis.notification.max-keywords:20}")
    private int maxKeywords;
    @Value("${axis.notification.max-keyword-length:30}")
    private int maxKeywordLength;

    @Transactional
    public Map<String, Object> list(UUID userId, boolean unreadOnly, int limit) {
        User user = authService.requireUser(userId);
        syncRecentCardNewsNotifications(user);
        int cappedLimit = Math.min(Math.max(1, limit), 50);
        PageRequest page = PageRequest.of(0, cappedLimit + 1);
        List<UserNotification> notifications = unreadOnly
                ? notificationRepository.findByUserIdAndReadAtIsNullAndDeletedAtIsNullOrderByCreatedAtDesc(userId, page)
                : notificationRepository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId, page);
        boolean hasNext = notifications.size() > cappedLimit;
        List<Map<String, Object>> items = notifications.stream()
                .limit(cappedLimit)
                .map(this::toItem)
                .toList();
        long unreadCount = notificationRepository.countByUserIdAndReadAtIsNullAndDeletedAtIsNull(userId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("items", items);
        response.put("unread_count", unreadCount);
        response.put("unreadCount", unreadCount);
        response.put("nextCursor", null);
        response.put("hasNext", hasNext);
        return response;
    }

    @Transactional
    public Map<String, Object> unreadCount(UUID userId) {
        User user = authService.requireUser(userId);
        syncRecentCardNewsNotifications(user);
        long count = notificationRepository.countByUserIdAndReadAtIsNullAndDeletedAtIsNull(userId);
        return Map.of("count", count);
    }

    @Transactional
    public Map<String, Object> markRead(UUID userId, UUID notificationId) {
        UserNotification notification = notificationRepository.findByIdAndUserIdAndDeletedAtIsNull(notificationId, userId)
                .orElseThrow(() -> new EntityNotFoundException("notification not found"));
        notification.markRead();
        return toItem(notification);
    }

    @Transactional
    public Map<String, Object> markAllRead(UUID userId) {
        authService.requireUser(userId);
        int updated = notificationRepository.markAllRead(userId);
        return Map.of("updated_count", updated, "updatedCount", updated);
    }

    @Transactional
    public Map<String, Object> deleteOne(UUID userId, UUID notificationId) {
        UserNotification notification = notificationRepository.findByIdAndUserIdAndDeletedAtIsNull(notificationId, userId)
                .orElseThrow(() -> new EntityNotFoundException("notification not found"));
        notification.markDeleted();
        return Map.of("deleted", true);
    }

    @Transactional
    public Map<String, Object> clear(UUID userId, String scope) {
        authService.requireUser(userId);
        String normalizedScope = scope == null || scope.isBlank() ? "READ" : scope.trim().toUpperCase();
        if (!List.of("READ", "ALL").contains(normalizedScope)) {
            throw new IllegalArgumentException("scope은 READ 또는 ALL만 사용할 수 있습니다.");
        }
        int deleted = notificationRepository.softDeleteByUserId(userId, "ALL".equals(normalizedScope));
        return Map.of("cleared", true, "scope", normalizedScope, "deleted_count", deleted, "deletedCount", deleted);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> preferences(UUID userId) {
        return normalizePreferences(authService.requireUser(userId).getNotificationPreferences());
    }

    @Transactional
    public Map<String, Object> updatePreferences(UUID userId, Map<String, Object> request) {
        var user = authService.requireUser(userId);
        Map<String, Object> normalized = normalizePreferences(request);
        user.updateNotificationPreferences(normalized);
        return normalized;
    }

    private Map<String, Object> toItem(UserNotification notification) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", notification.getId().toString());
        item.put("type", notification.getNotificationType());
        item.put("severity", notification.getSeverity());
        item.put("title", notification.getTitle());
        item.put("message", notification.getMessage());
        item.put("sourceType", notification.getSourceType());
        item.put("source_type", notification.getSourceType());
        item.put("sourceId", notification.getSourceId());
        item.put("source_id", notification.getSourceId());
        item.put("sourceUrl", notification.getSourceUrl());
        item.put("source_url", notification.getSourceUrl());
        item.put("companyName", notification.getCompanyName());
        item.put("company_name", notification.getCompanyName());
        item.put("matchedKeywords", notification.getMatchedKeywords());
        item.put("matched_keywords", notification.getMatchedKeywords());
        item.put("target", resolveTarget(notification));
        item.put("target_resource_id", notification.getTargetResourceId());
        item.put("payload", notification.getPayload());
        item.put("read", notification.getReadAt() != null);
        item.put("read_at", notification.getReadAt());
        item.put("createdAt", notification.getCreatedAt());
        item.put("created_at", notification.getCreatedAt());
        item.put("peer", notification.getCompanyName() == null || notification.getCompanyName().isBlank() ? "AXIS" : notification.getCompanyName());
        item.put("tone", "IMPORTANT".equals(notification.getSeverity()) ? "중요" : "알림");
        item.put("time", DateTimeFormatter.ofPattern("HH:mm")
                .withZone(ZoneId.of("Asia/Seoul"))
                .format(notification.getCreatedAt()));
        return item;
    }

    private void syncRecentCardNewsNotifications(User user) {
        Map<String, Object> preferences = normalizePreferences(user.getNotificationPreferences());
        if (!Boolean.TRUE.equals(preferences.get("enabled"))) {
            return;
        }
        @SuppressWarnings("unchecked")
        List<String> userKeywords = (List<String>) preferences.getOrDefault("keywords", List.of());
        List<String> systemKeywords = Boolean.TRUE.equals(preferences.get("importantEnabled"))
                ? normalizedKeywordList(preferences.get("importantKeywords"))
                : List.of();
        if (userKeywords.isEmpty() && systemKeywords.isEmpty()) {
            return;
        }

        for (CardNews card : cardNewsRepository.findTop50ByStatusOrderByCreatedAtDesc(CardNewsStatus.ACTIVE)) {
            createNotificationIfMatched(user, card, userKeywords, systemKeywords);
        }
    }

    private void createNotificationIfMatched(User user, CardNews card, List<String> userKeywords, List<String> systemKeywords) {
        String matchText = notificationMatchText(card);
        List<String> matchedUserKeywords = matchedKeywords(matchText, userKeywords);
        List<String> matchedImportantKeywords = matchedKeywords(matchText, systemKeywords);
        if (matchedUserKeywords.isEmpty() && matchedImportantKeywords.isEmpty()) {
            return;
        }

        List<String> matchedKeywords = new ArrayList<>();
        matchedKeywords.addAll(matchedImportantKeywords);
        matchedUserKeywords.stream()
                .filter(keyword -> matchedKeywords.stream().noneMatch(existing -> existing.equalsIgnoreCase(keyword)))
                .forEach(matchedKeywords::add);
        String companyName = companyName(card.getPeerId());
        boolean important = !matchedImportantKeywords.isEmpty();
        String title = notificationTitle(companyName, matchedKeywords, important);
        String message = notificationMessage(matchedKeywords, important);
        String contentHash = shortHash(matchText);
        String keywordHash = shortHash(String.join("|", matchedKeywords).toLowerCase(Locale.ROOT));
        String dedupeKey = "CARD_NEWS:" + card.getId() + ":" + contentHash + ":" + keywordHash;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("cardNewsTitle", card.getTitle());
        metadata.put("changedContentHash", contentHash);
        metadata.put("reason", important ? "IMPORTANT_KEYWORD_MATCHED" : "USER_KEYWORD_MATCHED");
        metadata.put("eventType", card.getEventType());
        metadata.put("importance", card.getImportance());

        if (notificationRepository.existsByUserIdAndDedupeKey(user.getId(), dedupeKey)) {
            return;
        }
        notificationRepository.save(UserNotification.create(
                user,
                important ? "IMPORTANT_SIGNAL" : "KEYWORD_MATCHED",
                important ? "IMPORTANT" : "NORMAL",
                title,
                message,
                "CARD_NEWS",
                card.getId(),
                "/card-news/" + card.getId(),
                companyName,
                matchedKeywords,
                metadata,
                dedupeKey,
                "issues",
                card.getId(),
                Map.of("source", "card_news", "cardNewsId", card.getId(), "matchedKeywords", matchedKeywords)
        ));
    }

    private String notificationMatchText(CardNews card) {
        List<String> parts = new ArrayList<>();
        parts.add(card.getTitle());
        parts.add(card.getPeerId());
        parts.add(card.getEventType());
        parts.add(card.getImportance());
        parts.add(card.getPrimaryKeywordCategory());
        if (card.getKeywords() != null) {
            parts.addAll(Arrays.stream(card.getKeywords()).filter(Objects::nonNull).toList());
        }
        if (card.getKeywordCategories() != null) {
            parts.add(card.getKeywordCategories().toString());
        }
        if (card.getKeywordFrequency() != null) {
            parts.add(card.getKeywordFrequency().toString());
        }
        if (card.getImplication() != null) {
            parts.add(card.getImplication().toString());
        }
        if (card.getEvidencePayload() != null) {
            parts.add(card.getEvidencePayload().toString());
        }
        if (card.getSourceArticles() != null) {
            parts.add(card.getSourceArticles().toString());
        }
        return normalizeForMatch(String.join(" ", parts.stream().filter(Objects::nonNull).toList()));
    }

    private List<String> matchedKeywords(String normalizedText, List<String> keywords) {
        return keywords.stream()
                .filter(keyword -> normalizedText.contains(normalizeForMatch(keyword)))
                .distinct()
                .toList();
    }

    private String normalizeForMatch(String value) {
        return Objects.toString(value, "")
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsHangul}\\p{Alnum}\\s]", " ")
                .replaceAll("\\s+", " ");
    }

    private List<String> defaultImportantKeywords() {
        List<String> configured = normalizeConfiguredKeywords(importantKeywords);
        return configured.isEmpty() ? User.DEFAULT_IMPORTANT_KEYWORDS : configured;
    }

    private List<String> normalizeConfiguredKeywords(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .map(value -> Objects.toString(value, "").trim().replaceAll("\\s+", " "))
                .filter(value -> value.length() >= 2)
                .distinct()
                .toList();
    }

    private String notificationTitle(String companyName, List<String> keywords, boolean important) {
        if (keywords.size() > 1) {
            return companyName + " · 주요 키워드 " + keywords.size() + "개 감지";
        }
        if (important) {
            return companyName + " · " + keywords.get(0);
        }
        return companyName + " · 관심 키워드 감지";
    }

    private String notificationMessage(List<String> keywords, boolean important) {
        if (keywords.size() > 1) {
            return keywords.get(0) + ", " + keywords.get(1) + " 등 관련 내용이 업데이트되었습니다.";
        }
        if (important) {
            return keywords.get(0) + " 관련 중요 신호가 감지되었습니다.";
        }
        return "\"" + keywords.get(0) + "\" 관련 내용이 카드뉴스에 업데이트되었습니다.";
    }

    private String companyName(String peerId) {
        return switch (Objects.toString(peerId, "")) {
            case "samsung_sds" -> "삼성SDS";
            case "lg_cns" -> "LG CNS";
            case "hyundai_autoever" -> "현대오토에버";
            case "posco_dx" -> "포스코DX";
            default -> peerId == null || peerId.isBlank() ? "AXIS" : peerId;
        };
    }

    private String shortHash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required for notification dedupe", e);
        }
    }

    private Map<String, Object> normalizePreferences(Map<String, Object> source) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("enabled", booleanValue(source, "enabled", true));
        normalized.put("importantEnabled", booleanValue(source, "importantEnabled", true));
        normalized.put("importantKeywords", normalizeImportantKeywords(source == null ? null : source.get("importantKeywords")));
        normalized.put("keywords", normalizeKeywords(source == null ? null : source.get("keywords")));
        return normalized;
    }

    private List<String> normalizeImportantKeywords(Object value) {
        if (value == null) {
            return defaultImportantKeywords();
        }
        return normalizeKeywords(value);
    }

    private List<String> normalizedKeywordList(Object value) {
        if (value instanceof List<?> values) {
            return values.stream()
                    .map(item -> Objects.toString(item, "").trim().replaceAll("\\s+", " "))
                    .filter(item -> !item.isBlank())
                    .distinct()
                    .toList();
        }
        return List.of();
    }

    private List<String> normalizeKeywords(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : values) {
            String keyword = Objects.toString(item, "").trim().replaceAll("\\s+", " ");
            if (keyword.isBlank()) {
                continue;
            }
            if (keyword.length() < 2) {
                throw new IllegalArgumentException("알림 키워드는 2자 이상 입력하세요.");
            }
            if (keyword.length() > maxKeywordLength) {
                throw new IllegalArgumentException("알림 키워드는 " + maxKeywordLength + "자 이하로 입력하세요.");
            }
            if (result.stream().noneMatch(existing -> existing.equalsIgnoreCase(keyword))) {
                result.add(keyword);
            }
        }
        if (result.size() > maxKeywords) {
            throw new IllegalArgumentException("알림 키워드는 최대 " + maxKeywords + "개까지 등록할 수 있습니다.");
        }
        return result;
    }

    private boolean booleanValue(Map<String, Object> source, String key, boolean defaultValue) {
        Object value = source == null ? null : source.get(key);
        return value instanceof Boolean bool ? bool : defaultValue;
    }

    private String resolveTarget(UserNotification notification) {
        if (notification.getTargetView() != null && !notification.getTargetView().isBlank()) {
            return notification.getTargetView();
        }
        String sourceType = notification.getSourceType() == null ? "" : notification.getSourceType();
        return switch (sourceType) {
            case "CARD_NEWS" -> "issues";
            case "BRIEFING" -> "briefings";
            case "PEER" -> "peerPlus";
            case "KEYWORD_GRAPH" -> "keywordGraph";
            default -> "home";
        };
    }
}
