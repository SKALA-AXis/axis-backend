package com.skala.axis.service;

import com.skala.axis.domain.User;
import com.skala.axis.domain.UserCardNewsBookmark;
import com.skala.axis.dto.CardNewsResponse;
import com.skala.axis.exception.AuthException;
import com.skala.axis.repository.CardNewsRepository;
import com.skala.axis.repository.UserCardNewsBookmarkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BookmarkService {
    private final AuthService authService;
    private final UserCardNewsBookmarkRepository bookmarkRepository;
    private final CardNewsRepository cardNewsRepository;
    private final CardNewsService cardNewsService;

    @Transactional(readOnly = true)
    public Map<String, Object> list(UUID userId, Map<String, String> params) {
        List<UserCardNewsBookmark> bookmarks = bookmarkRepository.findByUserIdOrderByCreatedAtDesc(userId);
        List<String> bookmarkedIds = bookmarks.stream()
                .map(UserCardNewsBookmark::getCardNewsId)
                .toList();
        Map<String, CardNewsResponse> cardsById = cardNewsRepository.findAllById(bookmarkedIds)
                .stream()
                .map(cardNewsService::toResponse)
                .collect(Collectors.toMap(CardNewsResponse::getId, Function.identity(), (left, right) -> left));
        List<CardNewsResponse> items = bookmarkedIds.stream()
                .map(cardsById::get)
                .filter(card -> card != null)
                .toList();
        return Map.of("items", items, "total", items.size());
    }

    @Transactional
    public Map<String, Object> add(UUID userId, Map<String, Object> request) {
        User user = authService.requireUser(userId);
        String cardId = cardId(request);
        if (!cardNewsRepository.existsById(cardId)) {
            throw new AuthException(HttpStatus.NOT_FOUND, "CARD_NEWS_NOT_FOUND", "존재하지 않는 카드뉴스입니다.");
        }
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
