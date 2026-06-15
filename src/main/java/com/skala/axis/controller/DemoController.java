package com.skala.axis.controller;

import com.skala.axis.domain.SentAlert;
import com.skala.axis.dto.ApiResponse;
import com.skala.axis.repository.SentAlertRepository;
import com.skala.axis.security.CronInternalAuth;
import com.skala.axis.service.EventAlertService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 시연용 — "중요 뉴스 게시" 데모 뉴스룸 페이지(클러스터 {@code /demo-newsroom})가 호출.
 *
 * <p>폼 입력을 {@link EventAlertService} 의 게이트→dedup→SES 발송 경로로 그대로 흘려보낸다
 * (card_news 미저장 — 운영 데이터 오염 없음). Bearer {@code ${CRON_INTERNAL_TOKEN}} 검증.
 * 발표 후 제거 가능한 throwaway 표면이다.</p>
 */
@RestController
@RequestMapping("/api/demo")
@RequiredArgsConstructor
public class DemoController {

    private final EventAlertService eventAlertService;
    private final SentAlertRepository sentAlertRepository;
    private final CronInternalAuth cronInternalAuth;

    @PostMapping("/publish")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publish(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) Map<String, Object> request
    ) {
        if (!cronInternalAuth.isAuthorized(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.success(Map.of("status", "unauthorized")));
        }
        Map<String, Object> body = request == null ? Map.of() : request;
        String title = str(body, "title");
        if (title == null || title.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("INVALID_REQUEST", "title 은 필수입니다."));
        }
        String eventType = str(body, "eventType");
        String peerId = str(body, "peerId");
        String summary = str(body, "summary");
        boolean hasFinancial = hasText(body.get("amount"));
        Float score = parseScore(body.get("score"));

        EventAlertService.AlertOutcome outcome =
                eventAlertService.evaluateDemo(peerId, eventType, title, summary, hasFinancial, score);

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "outcome", outcome.name(),
                "alerted", outcome == EventAlertService.AlertOutcome.SENT,
                "eventType", eventType == null ? "" : eventType,
                "title", title
        )));
    }

    @GetMapping("/sent-alerts")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> sentAlerts(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        if (!cronInternalAuth.isAuthorized(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.success(List.of()));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SentAlert alert : sentAlertRepository.findTop100ByOrderBySentAtDesc()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("eventType", alert.getEventType());
            row.put("title", alert.getTitle());
            row.put("peerId", alert.getPeerId());
            row.put("triggerSource", alert.getTriggerSource());
            row.put("sentAt", alert.getSentAt() == null ? null : alert.getSentAt().toString());
            rows.add(row);
        }
        return ResponseEntity.ok(ApiResponse.success(rows));
    }

    private static String str(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static boolean hasText(Object value) {
        return value != null && !String.valueOf(value).isBlank();
    }

    private static Float parseScore(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Float.parseFloat(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
