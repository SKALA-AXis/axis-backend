package com.skala.axis.controller;

import com.skala.axis.domain.SentAlert;
import com.skala.axis.dto.ApiResponse;
import com.skala.axis.repository.SentAlertRepository;
import com.skala.axis.security.CronInternalAuth;
import com.skala.axis.service.AiClientService;
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
 * <p>기사 원문(제목+본문)을 받아 <b>서비스가 스스로 분류</b>(axis-ai {@code /classify} —
 * 운영 수집 파이프라인과 동일 로직)한 뒤, 그 event_type·중요도로 {@link EventAlertService}
 * 게이트→dedup→SES 발송을 태운다. card_news 미저장(운영 데이터 오염 없음).
 * Bearer {@code ${CRON_INTERNAL_TOKEN}} 검증. 발표 후 제거 가능한 throwaway 표면.</p>
 */
@RestController
@RequestMapping("/api/demo")
@RequiredArgsConstructor
public class DemoController {

    private final EventAlertService eventAlertService;
    private final AiClientService aiClientService;
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
        String content = str(body, "content");
        String peerId = str(body, "peerId");

        // 1) 서비스가 스스로 분류 — 운영 수집 파이프라인과 동일 로직(axis-ai /classify).
        Map<String, Object> classified;
        try {
            classified = aiClientService.classifyArticle(title, content, peerId).block();
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "outcome", "CLASSIFY_FAILED",
                    "alerted", false,
                    "error", e.getMessage() == null ? "ai_unavailable" : e.getMessage(),
                    "title", title
            )));
        }
        if (classified == null) {
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "outcome", "CLASSIFY_FAILED", "alerted", false, "title", title)));
        }

        String eventType = str(classified, "event_type");
        Float score = toFloat(classified.get("importance_score"));
        String reasoning = str(classified, "reasoning");
        String summary = (reasoning == null || reasoning.isBlank())
                ? (content == null ? "" : content) : reasoning;

        // 2) 분류 결과(event_type·중요도)로 게이트→dedup→발송. 점수는 AI 판정값을 그대로 사용.
        EventAlertService.AlertOutcome outcome =
                eventAlertService.evaluateDemo(peerId, eventType, title, summary, false, score);

        // 3) 분류 결과 + 알림 판단을 함께 반환 — 데모에서 'AI가 스스로 판단' 을 가시화.
        Map<String, Object> classification = new LinkedHashMap<>();
        classification.put("eventType", eventType);
        classification.put("importance", str(classified, "importance"));
        classification.put("importanceScore", score);
        classification.put("sector", str(classified, "sector"));
        classification.put("reasoning", reasoning);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outcome", outcome.name());
        result.put("alerted", outcome == EventAlertService.AlertOutcome.SENT);
        result.put("classification", classification);
        result.put("title", title);
        return ResponseEntity.ok(ApiResponse.success(result));
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

    private static Float toFloat(Object value) {
        if (value instanceof Number number) {
            return number.floatValue();
        }
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
