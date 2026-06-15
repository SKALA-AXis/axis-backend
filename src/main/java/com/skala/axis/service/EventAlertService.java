package com.skala.axis.service;

import com.skala.axis.domain.CardNews;
import com.skala.axis.domain.CardNewsStatus;
import com.skala.axis.domain.SentAlert;
import com.skala.axis.repository.CardNewsRepository;
import com.skala.axis.repository.SentAlertRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 대형 이벤트(수주·파트너십·M&A) 1회성 이메일 알림.
 *
 * <p><b>정책 — 고정밀(high precision):</b> exposure/importance 점수는 정밀도가 낮아
 * 단독 신호로 쓰지 않는다. 게이트는 {@code event_type ∈ {ma, contract, partnership}}
 * (= 객관적으로 큰 사건 카테고리) 를 1차 조건으로, 거기에 "객관 보강 신호"
 * (importance_score ≥ 임계 또는 financial_refs 존재) 를 AND 로 요구한다. 자연히
 * 하루 0~수 건만 발동한다.</p>
 *
 * <p><b>1회만 발송:</b> 같은 사건은 dedup 단계에서 동일 cluster_id 로 묶이므로
 * {@code dedupe_key = "cluster:{id}"} 1건당 1알림. cluster 없으면 card/콘텐츠 기준.
 * {@code sent_alerts.dedupe_key} UNIQUE 가 DB 레벨 보장(예약-후-발송, 발송 실패 시
 * 예약 롤백 → 재시도 허용).</p>
 *
 * <p><b>발송:</b> 오직 {@link SesMailService}(AWS SES V2 + IRSA). axis-ai 무관
 * (card_news 만 읽음). 수신자는 일일 브리핑과 동일한 {@code briefing.recipients}.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventAlertService {

    private final CardNewsRepository cardNewsRepository;
    private final SentAlertRepository sentAlertRepository;
    private final SesMailService sesMailService;

    /** 마스터 스위치 — false 면 게이트만 평가하고 실제 발송/기록은 하지 않음. */
    @Value("${axis.alert.enabled:true}")
    private boolean alertEnabled;

    /** 알림 대상 event_type(소문자). 이 집합 밖은 절대 발송 안 함. */
    @Value("${axis.alert.event-types:ma,contract,partnership}")
    private List<String> alertEventTypes;

    /** importance_score 보강 임계. financial_refs 가 있으면 이 임계 미달도 통과. */
    @Value("${axis.alert.min-score:0.65}")
    private float minScore;

    /** 자동 스캔 시 거슬러 보는 분(分). */
    @Value("${axis.alert.scan-lookback-minutes:90}")
    private long scanLookbackMinutes;

    /** 보조 dedup — 같은 peer+event_type 을 최근 N일 내 발송했으면 억제. 0 이면 비활성. */
    @Value("${axis.alert.peer-event-suppress-days:0}")
    private int peerEventSuppressDays;

    /** 수신자 — 일일 브리핑과 공유(BRIEFING_RECIPIENTS). */
    @Value("${briefing.recipients:}")
    private String recipientsCsv;

    public enum AlertOutcome {
        SENT,
        SKIPPED_GATE,
        SKIPPED_DUPLICATE,
        SKIPPED_NO_RECIPIENTS,
        SKIPPED_DISABLED,
        FAILED
    }

    public record ScanResult(int candidates, int sent, int skipped, int failed) {
    }

    /** 자동/수동 스캔 — 최근 후보 카드 전수 평가. */
    public ScanResult scanRecent(String triggerSource) {
        LocalDateTime since = LocalDateTime.now().minusMinutes(scanLookbackMinutes);
        List<CardNews> candidates = cardNewsRepository.findAlertCandidates(
                since, CardNewsStatus.ACTIVE, normalizedEventTypes());

        int sent = 0;
        int failed = 0;
        for (CardNews card : candidates) {
            AlertOutcome outcome = evaluateAndSend(fromCard(card), triggerSource);
            if (outcome == AlertOutcome.SENT) {
                sent++;
            } else if (outcome == AlertOutcome.FAILED) {
                failed++;
            }
        }
        int skipped = candidates.size() - sent - failed;
        log.info("이벤트 알림 스캔 — 후보 {} 발송 {} 스킵 {} 실패 {} (since={}, source={})",
                candidates.size(), sent, skipped, failed, since, triggerSource);
        return new ScanResult(candidates.size(), sent, skipped, failed);
    }

    /**
     * 데모 인젝트 — 폼 입력으로 게이트→dedup→발송 경로를 그대로 태운다(카드 미저장).
     * 점수 미지정 시 통과값(0.95)으로 둬, 허용 event_type 데모 게시는 확실히 알림이 가게 한다
     * (비허용 event_type 은 게이트 1차에서 그대로 차단되어 정밀도 시연도 가능).
     */
    public AlertOutcome evaluateDemo(String peerId, String eventType, String title,
                                     String summary, boolean hasFinancialEvidence, Float score) {
        float effectiveScore = (score != null) ? score : 0.95f;
        AlertCandidate candidate = new AlertCandidate(
                null, null, blankToNull(peerId), normalize(eventType), title,
                effectiveScore, hasFinancialEvidence, summary);
        return evaluateAndSend(candidate, "demo");
    }

    AlertOutcome evaluateAndSend(AlertCandidate cand, String triggerSource) {
        if (!alertEnabled) {
            return AlertOutcome.SKIPPED_DISABLED;
        }
        if (!passesGate(cand)) {
            return AlertOutcome.SKIPPED_GATE;
        }

        String dedupeKey = dedupeKey(cand);
        if (sentAlertRepository.existsByDedupeKey(dedupeKey)) {
            return AlertOutcome.SKIPPED_DUPLICATE;
        }
        if (peerEventSuppressDays > 0 && cand.peerId() != null && cand.eventType() != null
                && sentAlertRepository.existsByPeerIdAndEventTypeAndSentAtAfter(
                        cand.peerId(), cand.eventType(),
                        LocalDateTime.now().minusDays(peerEventSuppressDays))) {
            return AlertOutcome.SKIPPED_DUPLICATE;
        }

        List<String> recipients = resolveRecipients();
        if (recipients.isEmpty()) {
            log.warn("이벤트 알림 스킵 — 수신자 미설정(briefing.recipients) title={}", cand.title());
            return AlertOutcome.SKIPPED_NO_RECIPIENTS;
        }

        String subject = buildSubject(cand);
        LocalDateTime now = LocalDateTime.now();

        // 예약(dedupe_key 선점) — 동시 스캔 경합은 UNIQUE 위반으로 한쪽만 통과.
        SentAlert reserved;
        try {
            reserved = sentAlertRepository.saveAndFlush(SentAlert.builder()
                    .id(UUID.randomUUID())
                    .dedupeKey(dedupeKey)
                    .cardNewsId(cand.cardId())
                    .clusterId(cand.clusterId())
                    .peerId(cand.peerId())
                    .eventType(cand.eventType())
                    .title(cand.title())
                    .importanceScore(cand.importanceScore())
                    .recipients(String.join(",", recipients))
                    .subject(subject)
                    .triggerSource(triggerSource)
                    .sesMessageId(null)
                    .status("sent")
                    .sentAt(now)
                    .createdAt(now)
                    .build());
        } catch (DataIntegrityViolationException duplicate) {
            return AlertOutcome.SKIPPED_DUPLICATE;
        }

        try {
            sesMailService.sendBriefing(recipients, subject, buildHtml(cand), buildText(cand));
            log.info("이벤트 알림 발송 — title={} eventType={} recipients={} source={}",
                    cand.title(), cand.eventType(), recipients.size(), triggerSource);
            return AlertOutcome.SENT;
        } catch (Exception e) {
            // 발송 실패 → 예약 롤백(다음 스캔에서 재시도 가능).
            log.error("이벤트 알림 발송 실패 — title={} error={}", cand.title(), e.getMessage(), e);
            sentAlertRepository.delete(reserved);
            return AlertOutcome.FAILED;
        }
    }

    // ── 게이트 ──────────────────────────────────────────────────────────────

    private boolean passesGate(AlertCandidate cand) {
        if (cand.title() == null || cand.title().isBlank()) {
            return false;
        }
        if (cand.eventType() == null || !normalizedEventTypes().contains(cand.eventType())) {
            return false; // 1차: 객관적 대형 이벤트 카테고리만
        }
        // 2차: 객관 보강 신호 — 금액 근거가 있거나, 점수가 임계 이상.
        boolean scoreOk = cand.importanceScore() != null && cand.importanceScore() >= minScore;
        return cand.hasFinancialEvidence() || scoreOk;
    }

    private String dedupeKey(AlertCandidate cand) {
        if (cand.clusterId() != null) {
            return "cluster:" + cand.clusterId();
        }
        if (cand.cardId() != null && !cand.cardId().isBlank()) {
            return "card:" + cand.cardId();
        }
        String basis = (cand.peerId() == null ? "" : cand.peerId()) + "|"
                + (cand.eventType() == null ? "" : cand.eventType()) + "|"
                + (cand.title() == null ? "" : cand.title().trim());
        return "content:" + Integer.toHexString(basis.hashCode());
    }

    private Set<String> normalizedEventTypes() {
        Set<String> set = new LinkedHashSet<>();
        if (alertEventTypes != null) {
            for (String type : alertEventTypes) {
                String normalized = normalize(type);
                if (!normalized.isEmpty()) {
                    set.add(normalized);
                }
            }
        }
        return set;
    }

    private List<String> resolveRecipients() {
        if (recipientsCsv == null || recipientsCsv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(recipientsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    // ── 이메일 본문 ─────────────────────────────────────────────────────────

    private String buildSubject(AlertCandidate cand) {
        return "[AXIS 🚨 중요 신호] " + eventLabel(cand.eventType()) + " — " + cand.title();
    }

    private String buildText(AlertCandidate cand) {
        StringBuilder sb = new StringBuilder("AXIS 중요 신호 감지\n\n");
        sb.append("유형: ").append(eventLabel(cand.eventType())).append('\n');
        if (cand.title() != null) {
            sb.append("제목: ").append(cand.title()).append('\n');
        }
        if (cand.summary() != null && !cand.summary().isBlank()) {
            sb.append("요약: ").append(cand.summary()).append('\n');
        }
        if (cand.importanceScore() != null) {
            sb.append("중요도 점수: ").append(String.format(Locale.ROOT, "%.2f", cand.importanceScore())).append('\n');
        }
        sb.append("\n이 알림은 누가 봐도 중요한 대형 이벤트(수주·파트너십·M&A)에 한해 1회 발송됩니다.\n");
        sb.append("— SK AX 사업전략팀 AXIS\n");
        return sb.toString();
    }

    private String buildHtml(AlertCandidate cand) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"ko\"><head><meta charset=\"UTF-8\"></head>");
        sb.append("<body style=\"font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;");
        sb.append("max-width:680px;margin:0 auto;padding:24px;color:#111827;\">");
        sb.append("<div style=\"display:inline-block;background:#b91c1c;color:#fff;font-size:12px;");
        sb.append("font-weight:600;padding:4px 10px;border-radius:4px;\">🚨 중요 신호</div>");
        sb.append("<h1 style=\"font-size:20px;margin:16px 0 4px;\">")
                .append(htmlEscape(eventLabel(cand.eventType()))).append("</h1>");
        sb.append("<p style=\"font-size:16px;margin:0 0 16px;color:#111827;\">")
                .append(htmlEscape(cand.title())).append("</p>");
        if (cand.summary() != null && !cand.summary().isBlank()) {
            sb.append("<p style=\"font-size:14px;color:#374151;line-height:1.6;\">")
                    .append(htmlEscape(cand.summary())).append("</p>");
        }
        sb.append("<table style=\"font-size:13px;color:#6b7280;margin-top:8px;border-collapse:collapse;\">");
        if (cand.peerId() != null) {
            sb.append(htmlRow("대상", cand.peerId()));
        }
        sb.append(htmlRow("유형", eventLabel(cand.eventType())));
        if (cand.importanceScore() != null) {
            sb.append(htmlRow("중요도", String.format(Locale.ROOT, "%.2f", cand.importanceScore())));
        }
        sb.append("</table>");
        sb.append("<footer style=\"margin-top:32px;padding-top:16px;border-top:1px solid #e5e7eb;");
        sb.append("color:#6b7280;font-size:12px;\">대형 이벤트(수주·파트너십·M&amp;A)에 한해 1회 발송 · ");
        sb.append("SK AX 사업전략팀 AXIS</footer>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private String htmlRow(String label, String value) {
        return "<tr><td style=\"padding:2px 12px 2px 0;\">" + htmlEscape(label) + "</td>"
                + "<td style=\"padding:2px 0;color:#111827;\">" + htmlEscape(value) + "</td></tr>";
    }

    private String eventLabel(String eventType) {
        if (eventType == null) {
            return "중요 이벤트";
        }
        return switch (eventType) {
            case "ma" -> "M&A·인수합병";
            case "contract" -> "수주·계약";
            case "partnership" -> "파트너십·제휴";
            default -> eventType;
        };
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────

    private static boolean hasFinancialRefs(Map<String, Object> evidencePayload) {
        if (evidencePayload == null) {
            return false;
        }
        Object refs = evidencePayload.get("financial_refs");
        if (refs instanceof List<?> list) {
            return !list.isEmpty();
        }
        if (refs instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return false;
    }

    private static String firstSummaryLine(CardNews card) {
        String[] lines = card.getSummaryLines();
        if (lines != null) {
            for (String line : lines) {
                if (line != null && !line.isBlank()) {
                    return line.trim();
                }
            }
        }
        return null;
    }

    private AlertCandidate fromCard(CardNews card) {
        return new AlertCandidate(
                card.getId(),
                card.getClusterId(),
                card.getPeerId(),
                normalize(card.getEventType()),
                card.getTitle(),
                card.getImportanceScore(),
                hasFinancialRefs(card.getEvidencePayload()),
                firstSummaryLine(card));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private static String htmlEscape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /** 게이트/발송 입력 — card_news 또는 데모 폼에서 매핑. */
    record AlertCandidate(
            String cardId,
            Long clusterId,
            String peerId,
            String eventType,
            String title,
            Float importanceScore,
            boolean hasFinancialEvidence,
            String summary
    ) {
    }
}
