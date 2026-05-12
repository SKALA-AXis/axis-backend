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
    }

    @Test
    void generateAndSendGroupsBriefingBySectorTrend() {
        when(cardNewsService.getTodayCards(null, null)).thenReturn(List.of(
                card("AX-1", "ax", 0.72f, "삼성SDS 제조 AX 플랫폼 확산"),
                card("SEC-1", "security", 0.61f, "LG CNS 클라우드 보안 관제 고도화"),
                card("INFRA-1", null, 0.47f, "현대오토에버 GPU 인프라 투자 확대")
        ));

        briefingService.generateAndSend();

        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> htmlCaptor = ArgumentCaptor.forClass(String.class);
        verify(sesMailService).sendBriefing(
                anyList(),
                anyString(),
                htmlCaptor.capture(),
                textCaptor.capture()
        );

        String text = textCaptor.getValue();
        assertThat(text)
                .contains("AXIS 오늘의 섹터별 브리핑")
                .contains("AX 경향 · 1건 · 강한 흐름 (0.72)")
                .contains("보안 경향 · 1건 · 형성 중 (0.61)")
                .contains("인프라 경향 · 1건 · 형성 중 (0.47)")
                .doesNotContain("urgent")
                .doesNotContain("notable")
                .doesNotContain("reference");

        String html = htmlCaptor.getValue();
        assertThat(html)
                .contains("<!DOCTYPE html")
                .contains("AXIS 오늘의 섹터별 브리핑")
                .contains("AX")
                .contains("보안");
    }

    @Test
    void generateAndSendSkipsWhenNoCards() {
        when(cardNewsService.getTodayCards(null, null)).thenReturn(List.of());

        briefingService.generateAndSend();

        verifyNoInteractions(sesMailService);
    }

    private CardNewsResponse card(String id, String sector, Float score, String title) {
        return CardNewsResponse.builder()
                .id(id)
                .peerId("samsung_sds")
                .sector(sector)
                .exposureScore(score)
                .title(title)
                .build();
    }
}
