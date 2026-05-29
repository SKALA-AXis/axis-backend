package com.skala.axis.service;

import com.skala.axis.domain.CardNews;
import com.skala.axis.domain.CardNewsStatus;
import com.skala.axis.domain.User;
import com.skala.axis.domain.UserCardNewsBookmark;
import com.skala.axis.dto.CardNewsResponse;
import com.skala.axis.exception.AuthException;
import com.skala.axis.repository.CardNewsRepository;
import com.skala.axis.repository.UserCardNewsBookmarkRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookmarkServiceTest {
    @Mock
    private AuthService authService;
    @Mock
    private UserCardNewsBookmarkRepository bookmarkRepository;
    @Mock
    private CardNewsRepository cardNewsRepository;
    @Mock
    private CardNewsService cardNewsService;

    @Test
    void listReturnsBookmarkedCardsFromCardNewsTableInBookmarkOrder() {
        UUID userId = UUID.randomUUID();
        User user = User.pending("owner@sk.com", "hash");
        UserCardNewsBookmark secondBookmark = UserCardNewsBookmark.create(user, "CARD-2", null);
        UserCardNewsBookmark firstBookmark = UserCardNewsBookmark.create(user, "CARD-1", null);
        CardNews firstCard = mockCard("CARD-1");
        CardNews secondCard = mockCard("CARD-2");
        BookmarkService service = bookmarkService();

        when(bookmarkRepository.findByUserIdOrderByCreatedAtDesc(userId))
                .thenReturn(List.of(secondBookmark, firstBookmark));
        when(cardNewsRepository.findAllById(List.of("CARD-2", "CARD-1")))
                .thenReturn(List.of(firstCard, secondCard));
        when(firstCard.getStatusOrDefault()).thenReturn(CardNewsStatus.ACTIVE);
        when(secondCard.getStatusOrDefault()).thenReturn(CardNewsStatus.ACTIVE);
        when(cardNewsService.toResponse(firstCard))
                .thenReturn(CardNewsResponse.builder().id("CARD-1").title("첫 번째 카드").build());
        when(cardNewsService.toResponse(secondCard))
                .thenReturn(CardNewsResponse.builder().id("CARD-2").title("두 번째 카드").build());

        Map<String, Object> result = service.list(userId, Map.of());

        @SuppressWarnings("unchecked")
        List<CardNewsResponse> items = (List<CardNewsResponse>) result.get("items");
        assertThat(items).extracting(CardNewsResponse::getId).containsExactly("CARD-2", "CARD-1");
        assertThat(result.get("total")).isEqualTo(2);
    }

    @Test
    void addStoresBookmarkWhenCardExists() {
        UUID userId = UUID.randomUUID();
        User user = User.pending("owner@sk.com", "hash");
        BookmarkService service = bookmarkService();
        when(authService.requireUser(userId)).thenReturn(user);
        when(cardNewsRepository.existsByIdAndStatus("CARD-1", CardNewsStatus.ACTIVE)).thenReturn(true);
        when(bookmarkRepository.existsByUserIdAndCardNewsId(userId, "CARD-1")).thenReturn(false);

        Map<String, Object> result = service.add(userId, Map.of("card_id", "CARD-1"));

        ArgumentCaptor<UserCardNewsBookmark> captor = ArgumentCaptor.forClass(UserCardNewsBookmark.class);
        verify(bookmarkRepository).save(captor.capture());
        assertThat(captor.getValue().getCardNewsId()).isEqualTo("CARD-1");
        assertThat(result).containsEntry("card_id", "CARD-1").containsEntry("bookmarked", true);
    }

    @Test
    void addRejectsMissingCardInsteadOfPersistingBrokenBookmark() {
        UUID userId = UUID.randomUUID();
        User user = User.pending("owner@sk.com", "hash");
        BookmarkService service = bookmarkService();
        when(authService.requireUser(userId)).thenReturn(user);
        when(cardNewsRepository.existsByIdAndStatus("MISSING", CardNewsStatus.ACTIVE)).thenReturn(false);

        assertThatThrownBy(() -> service.add(userId, Map.of("card_id", "MISSING")))
                .isInstanceOfSatisfying(AuthException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.getCode()).isEqualTo("CARD_NEWS_NOT_FOUND");
                });
    }

    private BookmarkService bookmarkService() {
        return new BookmarkService(authService, bookmarkRepository, cardNewsRepository, cardNewsService);
    }

    private CardNews mockCard(String id) {
        return mock(CardNews.class);
    }
}
