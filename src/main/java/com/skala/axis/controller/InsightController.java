package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import com.skala.axis.exception.AiServerException;
import com.skala.axis.service.AiClientService;
import com.skala.axis.service.ApiContractFixtureService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * InsightCascade 4-phase 분석 endpoint.
 *
 * <p>v3 변경: {@code POST /api/insights/generate} 가 fixture stub → axis-ai 의
 * {@code POST /insight/generate} 위임으로 wiring. axis-ai 미가용 / card_ids 미지정 시
 * fixture 로 graceful fallback (기존 frontend 의 polling 패턴 호환).</p>
 *
 * <p>spec: {@code axis-ai/design/30-analysis/insight-cascade.md} ·
 * {@code axis-ai/design/02-prompt-design-checklist.md §4} (3-tier observability).</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/insights")
@RequiredArgsConstructor
public class InsightController {
    private final ApiContractFixtureService fixture;
    private final AiClientService aiClientService;

    @GetMapping("/latest")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getLatestInsight(
            @RequestParam Map<String, String> params) {
        return ResponseEntity.ok(ApiResponse.success(fixture.latestInsight()));
    }

    /**
     * 분석 생성 요청 — axis-ai 의 InsightCascadeAgent 위임.
     *
     * <p>request body schema:
     * <pre>
     *   { "card_ids": ["IC-...", "IC-..."], "context": { ... } | null }
     * </pre>
     * </p>
     *
     * <p>response: axis-ai InsightCascadeAgent 출력 그대로 (cause / change / impact /
     * response / final_one_liner / sk_ax_implication / reasoning_trail / reasoning_steps /
     * langfuse_trace_id / follow_up_questions / risk_assumptions / confidence /
     * sources_used / peer_ids / provenance / warning).</p>
     */
    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> generateInsight(
            @RequestBody(required = false) Map<String, Object> request) {
        Map<String, Object> body = request != null ? request : Map.of();
        List<String> cardIds = parseCardIds(body);
        if (cardIds.isEmpty()) {
            log.info("Insight generate | card_ids 미지정 — fixture stub 반환");
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(ApiResponse.success(fixture.insightGenerationAccepted()));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> context = body.get("context") instanceof Map<?, ?> ctx
                ? (Map<String, Object>) ctx
                : Map.of();

        try {
            Map<String, Object> result = aiClientService.generateInsight(cardIds, context).block();
            if (result == null) {
                log.warn("Insight generate | axis-ai 응답 null — fixture fallback");
                return ResponseEntity.status(HttpStatus.ACCEPTED)
                        .body(ApiResponse.success(fixture.insightGenerationAccepted()));
            }
            log.info("Insight generate | cards={} confidence={}",
                    cardIds.size(), result.get("confidence"));
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(result));
        } catch (AiServerException e) {
            log.warn("Insight generate | axis-ai 호출 실패 — fixture fallback | {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(ApiResponse.success(fixture.insightGenerationAccepted()));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> parseCardIds(Map<String, Object> request) {
        Object raw = request.get("card_ids");
        if (raw instanceof List<?> list) {
            return list.stream()
                    .filter(o -> o instanceof String s && !s.isBlank())
                    .map(o -> (String) o)
                    .toList();
        }
        return List.of();
    }
}
