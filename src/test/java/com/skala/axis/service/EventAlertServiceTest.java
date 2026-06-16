package com.skala.axis.service;

import com.skala.axis.domain.CardNews;
import com.skala.axis.domain.SentAlert;
import com.skala.axis.repository.CardNewsRepository;
import com.skala.axis.repository.SentAlertRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventAlertServiceTest {

    @Mock
    private CardNewsRepository cardNewsRepository;

    @Mock
    private SentAlertRepository sentAlertRepository;

    @Mock
    private SesMailService sesMailService;

    @InjectMocks
    private EventAlertService eventAlertService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(eventAlertService, "alertEnabled", true);
        ReflectionTestUtils.setField(eventAlertService, "alertEventTypes",
                List.of("ma", "contract", "partnership"));
        ReflectionTestUtils.setField(eventAlertService, "minScore", 0.65f);
        ReflectionTestUtils.setField(eventAlertService, "scanLookbackMinutes", 90L);
        ReflectionTestUtils.setField(eventAlertService, "peerEventSuppressDays", 0);
        ReflectionTestUtils.setField(eventAlertService, "recipientsCsv", "team@example.com");
    }

    @Test
    void allowedEventTypeSendsAlert() {
        when(sentAlertRepository.existsByDedupeKey(anyString())).thenReturn(false);
        when(sentAlertRepository.saveAndFlush(any(SentAlert.class))).thenAnswer(inv -> inv.getArgument(0));

        EventAlertService.AlertOutcome outcome = eventAlertService.evaluateDemo(
                "samsung_sds", "contract", "삼성SDS 1조원 규모 차세대 시스템 수주", "대형 수주", true, null);

        assertThat(outcome).isEqualTo(EventAlertService.AlertOutcome.SENT);

        ArgumentCaptor<String> subjectCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> htmlCaptor = ArgumentCaptor.forClass(String.class);
        verify(sesMailService).sendBriefing(
                anyList(), subjectCaptor.capture(), htmlCaptor.capture(), anyString());
        assertThat(subjectCaptor.getValue())
                .contains("[AXIS 알림]")
                .doesNotContain("🚨")
                .doesNotContain("중요 신호")
                .contains("수주·계약")
                .contains("삼성SDS 1조원 규모 차세대 시스템 수주");
        assertThat(htmlCaptor.getValue())
                .contains("[AXIS 알림]")
                .contains("Samsung SDS")
                .doesNotContain("🚨")
                .doesNotContain("중요 신호")
                .doesNotContain("중요도");
    }

    @Test
    void disallowedEventTypeIsBlockedByGate() {
        EventAlertService.AlertOutcome outcome = eventAlertService.evaluateDemo(
                "samsung_sds", "personnel", "임원 인사 단행", "조직 개편", false, null);

        assertThat(outcome).isEqualTo(EventAlertService.AlertOutcome.SKIPPED_GATE);
        verifyNoInteractions(sesMailService);
        verify(sentAlertRepository, never()).saveAndFlush(any());
    }

    @Test
    void allowedTypeButLowScoreAndNoFinancialIsBlocked() {
        EventAlertService.AlertOutcome outcome = eventAlertService.evaluateDemo(
                "lg_cns", "partnership", "소규모 협력 논의", "초기 단계", false, 0.30f);

        assertThat(outcome).isEqualTo(EventAlertService.AlertOutcome.SKIPPED_GATE);
        verifyNoInteractions(sesMailService);
    }

    @Test
    void financialEvidenceOverridesLowScore() {
        when(sentAlertRepository.existsByDedupeKey(anyString())).thenReturn(false);
        when(sentAlertRepository.saveAndFlush(any(SentAlert.class))).thenAnswer(inv -> inv.getArgument(0));

        EventAlertService.AlertOutcome outcome = eventAlertService.evaluateDemo(
                "lg_cns", "ma", "LG CNS, 보안기업 지분 인수", "지분 인수", true, 0.30f);

        assertThat(outcome).isEqualTo(EventAlertService.AlertOutcome.SENT);
        verify(sesMailService).sendBriefing(anyList(), anyString(), anyString(), anyString());
    }

    @Test
    void duplicateDedupeKeyIsSkipped() {
        when(sentAlertRepository.existsByDedupeKey(anyString())).thenReturn(true);

        EventAlertService.AlertOutcome outcome = eventAlertService.evaluateDemo(
                "samsung_sds", "contract", "삼성SDS 대형 수주", "수주", true, null);

        assertThat(outcome).isEqualTo(EventAlertService.AlertOutcome.SKIPPED_DUPLICATE);
        verifyNoInteractions(sesMailService);
        verify(sentAlertRepository, never()).saveAndFlush(any());
    }

    @Test
    void noRecipientsIsSkipped() {
        ReflectionTestUtils.setField(eventAlertService, "recipientsCsv", "");
        when(sentAlertRepository.existsByDedupeKey(anyString())).thenReturn(false);

        EventAlertService.AlertOutcome outcome = eventAlertService.evaluateDemo(
                "samsung_sds", "contract", "삼성SDS 대형 수주", "수주", true, null);

        assertThat(outcome).isEqualTo(EventAlertService.AlertOutcome.SKIPPED_NO_RECIPIENTS);
        verifyNoInteractions(sesMailService);
        verify(sentAlertRepository, never()).saveAndFlush(any());
    }

    @Test
    void sendFailureRollsBackReservation() {
        when(sentAlertRepository.existsByDedupeKey(anyString())).thenReturn(false);
        when(sentAlertRepository.saveAndFlush(any(SentAlert.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new RuntimeException("ses down"))
                .when(sesMailService).sendBriefing(anyList(), anyString(), anyString(), anyString());

        EventAlertService.AlertOutcome outcome = eventAlertService.evaluateDemo(
                "samsung_sds", "ma", "삼성SDS, AI 스타트업 인수", "인수", true, null);

        assertThat(outcome).isEqualTo(EventAlertService.AlertOutcome.FAILED);
        verify(sentAlertRepository).delete(any(SentAlert.class));
    }

    @Test
    void disabledMasterSwitchSkips() {
        ReflectionTestUtils.setField(eventAlertService, "alertEnabled", false);

        EventAlertService.AlertOutcome outcome = eventAlertService.evaluateDemo(
                "samsung_sds", "contract", "삼성SDS 대형 수주", "수주", true, null);

        assertThat(outcome).isEqualTo(EventAlertService.AlertOutcome.SKIPPED_DISABLED);
        verifyNoInteractions(sesMailService);
        verifyNoInteractions(sentAlertRepository);
    }

    @Test
    void scanRecentSendsForQualifyingCard() {
        CardNews card = mock(CardNews.class);
        when(card.getId()).thenReturn("card-1");
        when(card.getClusterId()).thenReturn(42L);
        when(card.getPeerId()).thenReturn("samsung_sds");
        when(card.getEventType()).thenReturn("contract");
        when(card.getImportanceScore()).thenReturn(0.80f);
        when(card.getTitle()).thenReturn("삼성SDS 1조원 수주");
        when(card.getEvidencePayload()).thenReturn(null);
        when(card.getSummaryLines()).thenReturn(null);

        when(cardNewsRepository.findAlertCandidates(any(), any(), anyCollection()))
                .thenReturn(List.of(card));
        when(sentAlertRepository.existsByDedupeKey("cluster:42")).thenReturn(false);
        when(sentAlertRepository.saveAndFlush(any(SentAlert.class))).thenAnswer(inv -> inv.getArgument(0));

        EventAlertService.ScanResult result = eventAlertService.scanRecent("auto");

        assertThat(result.candidates()).isEqualTo(1);
        assertThat(result.sent()).isEqualTo(1);
        assertThat(result.skipped()).isZero();
        assertThat(result.failed()).isZero();
        verify(sesMailService).sendBriefing(anyList(), anyString(), anyString(), anyString());
    }
}
