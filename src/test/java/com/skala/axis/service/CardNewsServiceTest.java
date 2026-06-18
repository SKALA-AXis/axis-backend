/*
 * 작성일: 2026-06-12
 * 작성자: 박진
 * 변경이력:
 *   2026-06-12 박진 — 생성된 브리핑 워크플로 노출 기능 관련 테스트 추가
 *   2026-06-15 박지원 — 재무 수정 develop 머지 반영, 카드뉴스 날짜 표시 어긋남 수정 및 소스 발행 시각 기준 정렬 테스트 보강
 */
package com.skala.axis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skala.axis.domain.CardNews;
import com.skala.axis.domain.CardNewsStatus;
import com.skala.axis.domain.RawArticle;
import com.skala.axis.dto.CardNewsResponse;
import com.skala.axis.repository.CardNewsRepository;
import com.skala.axis.repository.RawArticleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CardNewsServiceTest {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Mock
    private CardNewsRepository cardNewsRepository;

    @Mock
    private RawArticleRepository rawArticleRepository;

    private CardNewsService service;

    @BeforeEach
    void setUp() {
        service = new CardNewsService(cardNewsRepository, rawArticleRepository, new ObjectMapper());
    }

    @Test
    void todayCardsUseRawArticlePublishedDateInsteadOfCardCreatedAt() {
        LocalDate today = LocalDate.now(KST);
        LocalDate yesterday = today.minusDays(1);

        CardNews sourceToday = card("CN-TODAY", 1L, yesterday.atTime(23, 30));
        CardNews sourceYesterday = card("CN-YESTERDAY", 2L, today.atTime(8, 30));
        RawArticle todayArticle = article(1L, today.atTime(9, 0));
        RawArticle yesterdayArticle = article(2L, yesterday.atTime(9, 0));

        when(cardNewsRepository.findByStatusOrderByCreatedAtDesc(CardNewsStatus.ACTIVE))
                .thenReturn(List.of(sourceToday, sourceYesterday));
        when(rawArticleRepository.findAllById(any()))
                .thenReturn(List.of(todayArticle, yesterdayArticle));

        List<CardNewsResponse> result = service.getTodayCards(null, null);

        assertThat(result).extracting(CardNewsResponse::getId).containsExactly("CN-TODAY");
        assertThat(result.get(0).getPublishedDate()).isEqualTo(today.toString());
    }

    @Test
    void todayCardsSortByEarliestSourcePublishedAtBeforeImportance() {
        LocalDate today = LocalDate.now(KST);
        CardNews olderHighImportance = card("CN-OLDER-HIGH", 1L, today.atTime(7, 0));
        CardNews newerLowImportance = card("CN-NEWER-LOW", 2L, today.atTime(8, 0));
        ReflectionTestUtils.setField(olderHighImportance, "importanceScore", 1.0f);
        ReflectionTestUtils.setField(newerLowImportance, "importanceScore", 0.1f);
        RawArticle olderArticle = article(1L, today.atTime(9, 0));
        RawArticle newerArticle = article(2L, today.atTime(11, 0));

        when(cardNewsRepository.findByStatusOrderByCreatedAtDesc(CardNewsStatus.ACTIVE))
                .thenReturn(List.of(olderHighImportance, newerLowImportance));
        when(rawArticleRepository.findAllById(any()))
                .thenReturn(List.of(olderArticle, newerArticle));

        List<CardNewsResponse> result = service.getTodayCards(null, null);

        assertThat(result).extracting(CardNewsResponse::getId)
                .containsExactly("CN-NEWER-LOW", "CN-OLDER-HIGH");
        assertThat(result.get(0).getPublishedAt())
                .isEqualTo(today + "T11:00:00+09:00");
    }

    @Test
    void localDateTimePublishedDateDoesNotShiftIntoNextKstDay() {
        CardNews card = card("CN-20260616-50213", 50213L, LocalDateTime.of(2026, 6, 16, 15, 8));
        RawArticle article = article(50213L, LocalDateTime.of(2026, 6, 16, 22, 0));

        when(cardNewsRepository.findByStatusOrderByCreatedAtDesc(CardNewsStatus.ACTIVE))
                .thenReturn(List.of(card));
        when(rawArticleRepository.findAllById(any()))
                .thenReturn(List.of(article));

        List<CardNewsResponse> result = service.getAll(null, null, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPublishedDate()).isEqualTo("2026-06-16");
        assertThat(result.get(0).getPublishedAt()).isEqualTo("2026-06-16T22:00:00+09:00");
        assertThat(result.get(0).getDate()).isEqualTo("2026.06.16");
    }

    private CardNews card(String id, Long rawArticleId, LocalDateTime createdAt) {
        CardNews card = instantiate(CardNews.class);
        ReflectionTestUtils.setField(card, "id", id);
        ReflectionTestUtils.setField(card, "peerId", "samsung_sds");
        ReflectionTestUtils.setField(card, "peerCompanyId", "samsung_sds");
        ReflectionTestUtils.setField(card, "title", id + " title");
        ReflectionTestUtils.setField(card, "summaryLines", new String[] {id + " summary"});
        ReflectionTestUtils.setField(card, "importanceScore", 0.7f);
        ReflectionTestUtils.setField(card, "importance", "high");
        ReflectionTestUtils.setField(card, "sourceRawArticleIds", new Long[] {rawArticleId});
        ReflectionTestUtils.setField(card, "primaryRawArticleId", rawArticleId);
        ReflectionTestUtils.setField(card, "status", CardNewsStatus.ACTIVE);
        ReflectionTestUtils.setField(card, "createdAt", createdAt);
        return card;
    }

    private RawArticle article(Long id, LocalDateTime publishedAt) {
        RawArticle article = instantiate(RawArticle.class);
        ReflectionTestUtils.setField(article, "id", id);
        ReflectionTestUtils.setField(article, "title", "source " + id);
        ReflectionTestUtils.setField(article, "url", "https://example.com/" + id);
        ReflectionTestUtils.setField(article, "publisher", "example");
        ReflectionTestUtils.setField(article, "publishedAt", publishedAt);
        return article;
    }

    private <T> T instantiate(Class<T> type) {
        try {
            Constructor<T> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
