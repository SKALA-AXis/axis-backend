/*
 * 작성일: 2026-04-21
 * 작성자: 최종민
 * 변경이력:
 *   2026-04-21 최종민 — 백엔드 베이스라인 작성, 이후 SES V2 메일 발송 통합·card_news 개편·브리핑 메일 양식 정비
 *   2026-05-12 박진 — 섹터별 오류 수정 및 챗봇 백엔드 지원 추가
 */
package com.skala.axis.service;

import com.skala.axis.dto.CardNewsResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 일일 브리핑 — Spring @Scheduled (08:30 KST MON-FRI) 가 호출.
 *
 * <p>v4 변경: 발송 채널 Slack → AWS SES V2 SDK (IRSA). sector-grouped 본문 빌더 보존.
 * 자세한 spec: {@code axis-infra/docs/SES_INTEGRATION.md} · ADR-0008.</p>
 * <p>v5 변경: issue_cards → card_news rename. DTO IssueCardResponse → CardNewsResponse.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BriefingService {
    private static final List<String> SECTOR_ORDER = List.of("ax", "security", "infra", "deal", "other");
    private static final Map<String, SectorDisplay> SECTOR_DISPLAY = Map.of(
            "ax", new SectorDisplay("AX", "AI 전환·자동화 경향"),
            "security", new SectorDisplay("보안", "보안·컴플라이언스 경향"),
            "infra", new SectorDisplay("인프라", "클라우드·데이터센터 경향"),
            "deal", new SectorDisplay("수주", "계약·투자·사업확장 경향"),
            "other", new SectorDisplay("기타", "기타 관찰 경향")
    );

    /** 일일 브리핑 본문에 섹터당 최대 노출 카드 수. importance_score desc 정렬 후 상위만. */
    private static final int MAX_CARDS_PER_SECTOR = 5;

    private final CardNewsService cardNewsService;
    private final SesMailService sesMailService;

    @Value("${briefing.recipients:}")
    private String briefingRecipientsCsv;

    @Value("${axis.auth.app-base-url:http://localhost:3100}")
    private String appBaseUrl;

    /** 비즈니스 푸터 문의처. */
    @Value("${axis.briefing.contact:axis.admin@sk.com}")
    private String contactEmail;

    public void generateAndSend() {
        List<CardNewsResponse> todayCards = cardNewsService.getTodayCards(null, null);
        if (todayCards.isEmpty()) {
            log.info("오늘의 카드 뉴스 없음. 브리핑 스킵.");
            return;
        }

        List<String> recipients = resolveRecipients();
        if (recipients.isEmpty()) {
            log.warn("수신자 미설정 (briefing.recipients env 비어있음) — 발송 스킵");
            return;
        }

        String subject = "[AXIS] 오늘의 동향 브리핑 — " + LocalDate.now();
        String text = buildBriefingText(todayCards);
        String html = buildBriefingHtml(todayCards);
        sesMailService.sendBriefing(recipients, subject, html, text);
        log.info("브리핑 발송 완료 — 카드 {}건, 수신자 {}명", todayCards.size(), recipients.size());
    }

    private List<String> resolveRecipients() {
        if (briefingRecipientsCsv == null || briefingRecipientsCsv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(briefingRecipientsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private String buildBriefingText(List<CardNewsResponse> cards) {
        Map<String, List<CardNewsResponse>> grouped = groupBySector(cards);
        StringBuilder sb = new StringBuilder("AXIS 오늘의 섹터별 브리핑\n");
        sb.append("오늘 감지된 동향 ")
                .append(cards.size())
                .append("건 중 섹터당 핵심 최대 ")
                .append(MAX_CARDS_PER_SECTOR)
                .append("건씩 정리했습니다.\n\n");

        for (String sector : orderedSectors(grouped)) {
            List<CardNewsResponse> sectorCards = grouped.get(sector).stream()
                    .sorted(Comparator.comparing(this::trendScore, Comparator.reverseOrder()))
                    .limit(MAX_CARDS_PER_SECTOR)
                    .toList();
            SectorDisplay display = SECTOR_DISPLAY.getOrDefault(sector, SECTOR_DISPLAY.get("other"));

            sb.append("■ ").append(display.label()).append(" 경향")
                    .append(" · ").append(sectorCards.size()).append("건")
                    .append("\n");
            sb.append("  ").append(display.description()).append("\n");

            for (CardNewsResponse card : sectorCards) {
                sb.append("- ");
                String badge = cardBadgeText(card);
                if (!badge.isBlank()) {
                    sb.append("[").append(badge).append("] ");
                }
                sb.append(card.getTitle());
                String imp = importanceLabel(card);
                if (!imp.isBlank()) {
                    sb.append(" (중요도 ").append(imp).append(")");
                }
                sb.append("\n");
                sb.append("  자세히 보기: ").append(cardNewsLink(card)).append("\n");
            }
            sb.append("\n");
        }
        sb.append("──────────────────────────────\n");
        sb.append("SK AX 사업전략팀 · AXIS (AX Intelligence Signal)\n");
        sb.append("본 메일은 발신 전용입니다. 문의: ").append(contactEmail).append("\n");
        sb.append("© 2026 SK AX\n");
        return sb.toString();
    }

    /**
     * 카드 line head 의 메타 badge — {@code "삼성SDS · 수주·계약"} (회사명 · 이벤트유형).
     *
     * <p>섹터는 이미 섹션 헤더라 생략. 회사 id/event_type 을 사람이 읽는 라벨로 변환한다.
     * 둘 다 없으면 빈 문자열.</p>
     */
    private String cardBadgeText(CardNewsResponse card) {
        List<String> parts = new ArrayList<>(2);
        String company = companyName(card.getPeerId());
        if (!company.isBlank()) {
            parts.add(company);
        }
        String eventLabel = eventTypeLabel(card.getEventType());
        if (!eventLabel.isBlank()) {
            parts.add(eventLabel);
        }
        return String.join(" · ", parts);
    }

    /** peer id → 사람이 읽는 회사명. (※ peer 하드코딩 — 향후 PeerCompanyProvider(B-R1)로 중앙화 예정) */
    private String companyName(String peerId) {
        if (peerId == null || peerId.isBlank()) {
            return "";
        }
        return switch (peerId.trim().toLowerCase(Locale.ROOT)) {
            case "samsung_sds" -> "삼성SDS";
            case "lg_cns" -> "LG CNS";
            case "hyundai_autoever" -> "현대오토에버";
            case "posco_dx" -> "포스코DX";
            case "nvidia" -> "NVIDIA";
            case "apple" -> "Apple";
            case "microsoft" -> "Microsoft";
            case "google" -> "Google";
            case "amazon" -> "Amazon";
            case "meta" -> "Meta";
            default -> peerId;
        };
    }

    /** event_type → 사람이 읽는 라벨. */
    private String eventTypeLabel(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return "";
        }
        return switch (eventType.trim().toLowerCase(Locale.ROOT)) {
            case "contract" -> "수주·계약";
            case "ma" -> "M&A·인수";
            case "partnership" -> "파트너십";
            case "personnel" -> "인사·조직";
            case "tech_release", "tech" -> "기술·제품";
            case "regulation" -> "규제·정책";
            case "financial" -> "실적·재무";
            case "expansion" -> "사업확장";
            case "new_biz" -> "신규사업";
            case "company" -> "기업동향";
            default -> eventType;
        };
    }

    /** 중요도 밴드 → 정성 라벨(높음/중간/낮음). band 없으면 점수로 추정. */
    private String importanceLabel(CardNewsResponse card) {
        String band = card.getImportance();
        if (band == null || band.isBlank()) {
            Float score = card.getImportanceScore() != null ? card.getImportanceScore() : card.getExposureScore();
            if (score == null) {
                return "";
            }
            band = score >= 0.65f ? "high" : score >= 0.40f ? "medium" : "low";
        }
        return switch (band.trim().toLowerCase(Locale.ROOT)) {
            case "high" -> "높음";
            case "medium" -> "중간";
            case "low" -> "낮음";
            default -> "";
        };
    }

    private String buildBriefingHtml(List<CardNewsResponse> cards) {
        Map<String, List<CardNewsResponse>> grouped = groupBySector(cards);
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"ko\"><head><meta charset=\"UTF-8\"></head>");
        sb.append("<body style=\"font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;");
        sb.append("max-width:680px;margin:0 auto;padding:24px;color:#111827;\">");
        sb.append("<header style=\"border-bottom:2px solid #111827;padding-bottom:16px;margin-bottom:24px;\">");
        sb.append("<h1 style=\"margin:0;font-size:22px;\">AXIS 오늘의 섹터별 브리핑</h1>");
        sb.append("<p style=\"margin:8px 0 0;color:#6b7280;font-size:14px;\">")
                .append(LocalDate.now()).append(" · 전체 ").append(cards.size())
                .append("건 · 섹터당 핵심 최대 ").append(MAX_CARDS_PER_SECTOR).append("건</p>");
        sb.append("</header>");

        for (String sector : orderedSectors(grouped)) {
            List<CardNewsResponse> sectorCards = grouped.get(sector).stream()
                    .sorted(Comparator.comparing(this::trendScore, Comparator.reverseOrder()))
                    .limit(MAX_CARDS_PER_SECTOR)
                    .toList();
            SectorDisplay display = SECTOR_DISPLAY.getOrDefault(sector, SECTOR_DISPLAY.get("other"));

            sb.append("<section style=\"margin-bottom:24px;\">");
            sb.append("<h2 style=\"font-size:16px;margin:0 0 4px;color:#111827;\">")
                    .append(htmlEscape(display.label())).append(" 경향 · ")
                    .append(sectorCards.size()).append("건</h2>");
            sb.append("<p style=\"margin:0 0 12px;color:#6b7280;font-size:13px;\">")
                    .append(htmlEscape(display.description())).append("</p>");
            sb.append("<ul style=\"list-style:none;padding:0;margin:0;\">");
            for (CardNewsResponse card : sectorCards) {
                sb.append("<li style=\"padding:10px 0;border-bottom:1px solid #e5e7eb;\">");
                String badge = cardBadgeText(card);
                String imp = importanceLabel(card);
                sb.append("<div style=\"margin-bottom:4px;\">");
                if (!badge.isBlank()) {
                    sb.append("<span style=\"color:#6b7280;font-size:12px;\">")
                            .append(htmlEscape(badge)).append("</span>");
                }
                if (!imp.isBlank()) {
                    sb.append("<span style=\"margin-left:6px;font-size:11px;font-weight:600;color:#ffffff;")
                            .append("background:#1d4ed8;padding:1px 8px;border-radius:10px;\">")
                            .append(htmlEscape(imp)).append("</span>");
                }
                sb.append("</div>");
                String link = htmlEscape(cardNewsLink(card));
                sb.append("<a href=\"").append(link)
                        .append("\" style=\"color:#111827;text-decoration:none;font-weight:600;font-size:15px;\">")
                        .append(htmlEscape(card.getTitle())).append("</a>");
                sb.append("<div style=\"margin-top:3px;\"><a href=\"").append(link)
                        .append("\" style=\"color:#1d4ed8;text-decoration:none;font-size:12px;\">카드 자세히 보기 →</a></div>");
                sb.append("</li>");
            }
            sb.append("</ul></section>");
        }

        sb.append("<footer style=\"margin-top:32px;padding-top:16px;border-top:1px solid #e5e7eb;");
        sb.append("color:#6b7280;font-size:12px;line-height:1.7;\">");
        sb.append("<div style=\"font-weight:600;color:#374151;\">SK AX 사업전략팀 · AXIS</div>");
        sb.append("<div>AX Intelligence Signal — Peer사 전략 동향 자동 브리핑</div>");
        sb.append("<div>본 메일은 발신 전용입니다. 문의: <a href=\"mailto:")
                .append(htmlEscape(contactEmail)).append("\" style=\"color:#1d4ed8;\">")
                .append(htmlEscape(contactEmail)).append("</a></div>");
        sb.append("<div style=\"margin-top:6px;color:#9ca3af;\">© 2026 SK AX. All rights reserved.</div>");
        sb.append("</footer>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private static String htmlEscape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String cardNewsLink(CardNewsResponse card) {
        String encodedId = URLEncoder.encode(card.getId(), StandardCharsets.UTF_8);
        return trimTrailingSlash(appBaseUrl) + "/issues?card=" + encodedId;
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:3100";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private Map<String, List<CardNewsResponse>> groupBySector(List<CardNewsResponse> cards) {
        Map<String, List<CardNewsResponse>> grouped = new LinkedHashMap<>();
        for (CardNewsResponse card : cards) {
            String sector = resolveSector(card);
            grouped.computeIfAbsent(sector, ignored -> new ArrayList<>()).add(card);
        }
        return grouped;
    }

    private List<String> orderedSectors(Map<String, List<CardNewsResponse>> grouped) {
        List<String> ordered = new ArrayList<>(SECTOR_ORDER.stream()
                .filter(grouped::containsKey)
                .toList());
        grouped.keySet().stream()
                .filter(sector -> !ordered.contains(sector))
                .sorted()
                .forEach(ordered::add);
        return ordered;
    }

    private String resolveSector(CardNewsResponse card) {
        String sector = normalizeSector(card.getSector());
        if (!"other".equals(sector)) {
            return sector;
        }
        if (card.getSectors() != null) {
            for (String candidate : card.getSectors()) {
                sector = normalizeSector(candidate);
                if (!"other".equals(sector)) {
                    return sector;
                }
            }
        }
        return inferSector(card.getTitle());
    }

    private String normalizeSector(String value) {
        if (value == null || value.isBlank()) {
            return "other";
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "ax", "ai", "ai_tech", "sk_ax_biz" -> "ax";
            case "security", "sec", "보안" -> "security";
            case "infra", "infrastructure", "cloud", "인프라" -> "infra";
            case "deal", "deals", "large_deal", "biz_area", "contract", "수주" -> "deal";
            default -> "other";
        };
    }

    private String inferSector(String title) {
        if (title == null || title.isBlank()) {
            return "other";
        }
        String text = title.toLowerCase(Locale.ROOT);
        if (containsAny(text, "보안", "사이버", "정보보호", "제로트러스트", "xdr", "edr", "soc")) {
            return "security";
        }
        if (containsAny(text, "인프라", "클라우드", "데이터센터", "gpu", "msp", "kubernetes", "쿠버네티스")) {
            return "infra";
        }
        if (containsAny(text, "수주", "계약", "mou", "협약", "인수", "합병", "투자", "사업자 선정")) {
            return "deal";
        }
        if (containsAny(text, "ax", "ai 전환", "ai 혁신", "디지털 전환", "생성형 ai", "llm", "rag", "에이전틱")) {
            return "ax";
        }
        return "other";
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private Float trendScore(CardNewsResponse card) {
        if (card.getExposureScore() != null) {
            return card.getExposureScore();
        }
        if (card.getImportanceScore() != null) {
            return card.getImportanceScore();
        }
        return 0.0f;
    }

    private record SectorDisplay(String label, String description) {
    }
}
