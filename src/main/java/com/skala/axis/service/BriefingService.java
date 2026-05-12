package com.skala.axis.service;

import com.skala.axis.dto.IssueCardResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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

    private final IssueCardService issueCardService;
    private final SesMailService sesMailService;

    @Value("${briefing.recipients:}")
    private String briefingRecipientsCsv;

    public void generateAndSend() {
        List<IssueCardResponse> todayIssues = issueCardService.getTodayIssues(null, null);
        if (todayIssues.isEmpty()) {
            log.info("오늘의 이슈 카드 없음. 브리핑 스킵.");
            return;
        }

        List<String> recipients = resolveRecipients();
        if (recipients.isEmpty()) {
            log.warn("수신자 미설정 (briefing.recipients env 비어있음) — 발송 스킵");
            return;
        }

        String subject = "[AXIS] 오늘의 동향 브리핑 — " + LocalDate.now();
        String text = buildBriefingText(todayIssues);
        String html = buildBriefingHtml(todayIssues);
        sesMailService.sendBriefing(recipients, subject, html, text);
        log.info("브리핑 발송 완료 — 이슈 {}건, 수신자 {}명", todayIssues.size(), recipients.size());
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

    private String buildBriefingText(List<IssueCardResponse> issues) {
        Map<String, List<IssueCardResponse>> grouped = groupBySector(issues);
        StringBuilder sb = new StringBuilder("AXIS 오늘의 섹터별 브리핑\n");
        sb.append("오늘 감지된 동향 ")
                .append(issues.size())
                .append("건을 섹터 경향별로 정리했습니다.\n\n");

        for (String sector : orderedSectors(grouped)) {
            List<IssueCardResponse> sectorIssues = grouped.get(sector).stream()
                    .sorted(Comparator.comparing(this::trendScore, Comparator.reverseOrder()))
                    .toList();
            SectorDisplay display = SECTOR_DISPLAY.getOrDefault(sector, SECTOR_DISPLAY.get("other"));

            sb.append("■ ").append(display.label()).append(" 경향")
                    .append(" · ").append(sectorIssues.size()).append("건")
                    .append(" · ").append(trendLabel(sectorIssues))
                    .append("\n");
            sb.append("  ").append(display.description()).append("\n");

            for (IssueCardResponse issue : sectorIssues) {
                sb.append("- ");
                if (issue.getPeerId() != null && !issue.getPeerId().isBlank()) {
                    sb.append("[").append(issue.getPeerId()).append("] ");
                }
                sb.append(issue.getTitle()).append("\n");
            }
            sb.append("\n");
        }
        sb.append("— SK AX 사업전략팀 AXIS\n");
        return sb.toString();
    }

    private String buildBriefingHtml(List<IssueCardResponse> issues) {
        Map<String, List<IssueCardResponse>> grouped = groupBySector(issues);
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"ko\"><head><meta charset=\"UTF-8\"></head>");
        sb.append("<body style=\"font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;");
        sb.append("max-width:680px;margin:0 auto;padding:24px;color:#111827;\">");
        sb.append("<header style=\"border-bottom:2px solid #111827;padding-bottom:16px;margin-bottom:24px;\">");
        sb.append("<h1 style=\"margin:0;font-size:22px;\">AXIS 오늘의 섹터별 브리핑</h1>");
        sb.append("<p style=\"margin:8px 0 0;color:#6b7280;font-size:14px;\">")
                .append(LocalDate.now()).append(" · ").append(issues.size()).append("건</p>");
        sb.append("</header>");

        for (String sector : orderedSectors(grouped)) {
            List<IssueCardResponse> sectorIssues = grouped.get(sector).stream()
                    .sorted(Comparator.comparing(this::trendScore, Comparator.reverseOrder()))
                    .toList();
            SectorDisplay display = SECTOR_DISPLAY.getOrDefault(sector, SECTOR_DISPLAY.get("other"));

            sb.append("<section style=\"margin-bottom:24px;\">");
            sb.append("<h2 style=\"font-size:16px;margin:0 0 4px;color:#111827;\">")
                    .append(htmlEscape(display.label())).append(" 경향 · ")
                    .append(sectorIssues.size()).append("건 · ")
                    .append(htmlEscape(trendLabel(sectorIssues))).append("</h2>");
            sb.append("<p style=\"margin:0 0 12px;color:#6b7280;font-size:13px;\">")
                    .append(htmlEscape(display.description())).append("</p>");
            sb.append("<ul style=\"list-style:none;padding:0;margin:0;\">");
            for (IssueCardResponse issue : sectorIssues) {
                sb.append("<li style=\"padding:8px 0;border-bottom:1px solid #e5e7eb;\">");
                if (issue.getPeerId() != null && !issue.getPeerId().isBlank()) {
                    sb.append("<span style=\"color:#6b7280;font-size:13px;\">[")
                            .append(htmlEscape(issue.getPeerId())).append("]</span> ");
                }
                sb.append(htmlEscape(issue.getTitle()));
                sb.append("</li>");
            }
            sb.append("</ul></section>");
        }

        sb.append("<footer style=\"margin-top:32px;padding-top:16px;border-top:1px solid #e5e7eb;");
        sb.append("color:#6b7280;font-size:12px;\">SK AX 사업전략팀 AXIS · 자동 발송</footer>");
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

    private Map<String, List<IssueCardResponse>> groupBySector(List<IssueCardResponse> issues) {
        Map<String, List<IssueCardResponse>> grouped = new LinkedHashMap<>();
        for (IssueCardResponse issue : issues) {
            String sector = resolveSector(issue);
            grouped.computeIfAbsent(sector, ignored -> new ArrayList<>()).add(issue);
        }
        return grouped;
    }

    private List<String> orderedSectors(Map<String, List<IssueCardResponse>> grouped) {
        List<String> ordered = new ArrayList<>(SECTOR_ORDER.stream()
                .filter(grouped::containsKey)
                .toList());
        grouped.keySet().stream()
                .filter(sector -> !ordered.contains(sector))
                .sorted()
                .forEach(ordered::add);
        return ordered;
    }

    private String resolveSector(IssueCardResponse issue) {
        String sector = normalizeSector(issue.getSector());
        if (!"other".equals(sector)) {
            return sector;
        }
        if (issue.getSectors() != null) {
            for (String candidate : issue.getSectors()) {
                sector = normalizeSector(candidate);
                if (!"other".equals(sector)) {
                    return sector;
                }
            }
        }
        return inferSector(issue.getTitle());
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

    private String trendLabel(List<IssueCardResponse> issues) {
        Float score = issues.stream()
                .map(this::trendScore)
                .max(Float::compareTo)
                .orElse(0.0f);
        String label;
        if (score >= 0.65f) {
            label = "강한 흐름";
        } else if (score >= 0.40f) {
            label = "형성 중";
        } else {
            label = "관찰 흐름";
        }
        return label + " (" + String.format(Locale.ROOT, "%.2f", score) + ")";
    }

    private Float trendScore(IssueCardResponse issue) {
        if (issue.getExposureScore() != null) {
            return issue.getExposureScore();
        }
        if (issue.getImportanceScore() != null) {
            return issue.getImportanceScore();
        }
        return 0.0f;
    }

    private record SectorDisplay(String label, String description) {
    }
}
