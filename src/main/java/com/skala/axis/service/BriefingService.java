package com.skala.axis.service;

import com.skala.axis.dto.IssueCardResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BriefingService {
    private final IssueCardService issueCardService;
    private final SlackService slackService;

    public void generateAndSend() {
        List<IssueCardResponse> todayIssues = issueCardService.getTodayIssues(null, null);
        if (todayIssues.isEmpty()) {
            log.info("오늘의 이슈 카드 없음. 브리핑 스킵.");
            return;
        }
        String briefing = buildBriefingText(todayIssues);
        slackService.sendMessage(briefing);
        log.info("브리핑 전송 완료. 이슈 {}건", todayIssues.size());
    }

    private String buildBriefingText(List<IssueCardResponse> issues) {
        StringBuilder sb = new StringBuilder("📊 *AXIS 오늘의 브리핑*\n\n");
        for (IssueCardResponse issue : issues) {
            String emoji = switch (issue.getImportance() != null ? issue.getImportance() : "reference") {
                case "urgent" -> "🔴";
                case "notable" -> "🟡";
                default -> "🟢";
            };
            sb.append(emoji).append(" ").append(issue.getTitle()).append("\n");
        }
        return sb.toString();
    }
}
