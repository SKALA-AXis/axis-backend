package com.skala.axis.service;

import com.skala.axis.domain.User;
import com.skala.axis.domain.UserCardNewsBookmark;
import com.skala.axis.repository.UserCardNewsBookmarkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BookmarkService {
    private final AuthService authService;
    private final UserCardNewsBookmarkRepository bookmarkRepository;
    private final ApiContractFixtureService fixture;

    @Transactional(readOnly = true)
    public Map<String, Object> list(UUID userId, Map<String, String> params) {
        List<String> bookmarkedIds = bookmarkRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(UserCardNewsBookmark::getCardNewsId)
                .toList();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cards = (List<Map<String, Object>>) fixture.todayCards(params).get("items");
        List<Map<String, Object>> items = cards.stream()
                .filter(card -> bookmarkedIds.contains(String.valueOf(card.get("id"))))
                .toList();
        return Map.of("items", items, "total", items.size());
    }

    @Transactional
    public Map<String, Object> add(UUID userId, Map<String, Object> request) {
        User user = authService.requireUser(userId);
        String cardId = cardId(request);
        if (!bookmarkRepository.existsByUserIdAndCardNewsId(userId, cardId)) {
            bookmarkRepository.save(UserCardNewsBookmark.create(user, cardId, stringValue(request, "note")));
        }
        return new LinkedHashMap<>(Map.of("card_id", cardId, "bookmarked", true));
    }

    @Transactional
    public Map<String, Object> remove(UUID userId, String cardId) {
        authService.requireUser(userId);
        bookmarkRepository.deleteByUserIdAndCardNewsId(userId, cardId);
        return new LinkedHashMap<>(Map.of("card_id", cardId, "bookmarked", false));
    }

    private String cardId(Map<String, Object> request) {
        String cardId = stringValue(request, "card_id");
        if (cardId == null || cardId.isBlank()) {
            cardId = stringValue(request, "cardId");
        }
        if (cardId == null || cardId.isBlank()) {
            throw new IllegalArgumentException("card_id is required");
        }
        return cardId;
    }

    private String stringValue(Map<String, Object> request, String key) {
        Object value = request == null ? null : request.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
