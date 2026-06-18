/*
 * 작성일: 2026-05-12
 * 작성자: 박진
 * 변경이력:
 *   2026-05-12 박진 — 섹터별 오류 수정과 함께 추가, 이후 사용자 챗봇 백엔드 지원 반영
 *   2026-05-12 최종민 — SesMailService mock 전환·섹터 텍스트 형식 변경, issue_cards→card_news 리네임, 브리핑 메일 양식 비즈니스화 반영
 */
package com.skala.axis.service;

import com.skala.axis.dto.CardNewsResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BriefingServiceTest {
    @Mock
    private CardNewsService cardNewsService;

    @Mock
    private SesMailService sesMailService;

    @InjectMocks
    private BriefingService briefingService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(briefingService, "briefingRecipientsCsv", "test@example.com");
        ReflectionTestUtils.setField(briefingService, "appBaseUrl", "https://axis.example");
        ReflectionTestUtils.setField(briefingService, "contactEmail", "axis.admin@sk.com");
    }

    @Test
    void generateAndSendUsesIntuitiveLabelsAndBusinessFooter() {
        when(cardNewsService.getTodayCards(null, null)).thenReturn(List.of(
                card("AX-1", "ax", "tech_release", 0.72f, "삼성SDS 제조 AX 플랫폼 확산"),
                card("SEC-1", "security", "contract", 0.61f, "LG CNS 클라우드 보안 관제 고도화"),
                card("INFRA-1", null, "partnership", 0.47f, "현대오토에버 GPU 인프라 투자 확대")
        ));

        briefingService.generateAndSend();

        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> htmlCaptor = ArgumentCaptor.forClass(String.class);
        verify(sesMailService).sendBriefing(
                anyList(), anyString(), htmlCaptor.capture(), textCaptor.capture());

        String text = textCaptor.getValue();
        assertThat(text)
                .contains("삼성SDS")                                // 회사명 라벨 (peer id 아님)
                .doesNotContain("samsung_sds")
                .contains("중요도 높음")                            // 정성 등급
                .contains("https://axis.example/issues?card=AX-1")   // 카드 딥링크
                .contains("문의: axis.admin@sk.com")                 // 비즈니스 푸터
                .contains("발신 전용")
                .doesNotContain("강한 흐름")
                .doesNotContain("0.72");

        String html = htmlCaptor.getValue();
        assertThat(html)
                .contains("<!DOCTYPE html")
                .contains("삼성SDS")
                .doesNotContain("samsung_sds")
                .contains("href=\"https://axis.example/issues?card=AX-1\"")
                .contains("mailto:axis.admin@sk.com")
                .doesNotContain("0.72");
    }

    @Test
    void generateAndSendSkipsWhenNoCards() {
        when(cardNewsService.getTodayCards(null, null)).thenReturn(List.of());

        briefingService.generateAndSend();

        verifyNoInteractions(sesMailService);
    }

    private CardNewsResponse card(String id, String sector, String eventType, Float score, String title) {
        return CardNewsResponse.builder()
                .id(id)
                .peerId("samsung_sds")
                .sector(sector)
                .eventType(eventType)
                .exposureScore(score)
                .title(title)
                .build();
    }
}
