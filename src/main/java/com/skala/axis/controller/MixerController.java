package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import com.skala.axis.exception.AiServerException;
import com.skala.axis.service.AiClientService;
import com.skala.axis.service.ApiContractFixtureService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * MixerAnalysis endpoint.
 *
 * <p>v2 변경: {@code POST /api/mixer} 가 fixture stub → axis-ai 의
 * {@code POST /mixer/analyze} 위임으로 wiring. axis-ai 미가용 / card_ids 부족 시
 * fixture 로 graceful fallback (기존 frontend 호환).</p>
 *
 * <p>spec: {@code axis-ai/design/30-analysis/mixer-analysis.md}.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/mixer")
@RequiredArgsConstructor
public class MixerController {
    private final ApiContractFixtureService fixture;
    private final AiClientService aiClientService;

    @GetMapping("/options")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMixerOptions() {
        return ResponseEntity.ok(ApiResponse.success(fixture.mixerOptions()));
    }

    /**
     * 카드 조합 분석 — axis-ai 의 MixerAnalysisAgent 위임.
     *
     * <p>request body schema:
     * <pre>
     *   { "card_ids": ["CN-...", ...],
     *     "ratios": {"peer": {...}, "industry": {...}, "keyword": [...]} | null,
     *     "user_context": "..." | null }
     * </pre>
     *
     * <p>response: axis-ai MixerAnalysisAgent 출력 그대로 (mix_id / insight /
     * final_one_liner / sk_ax_implication / bullet_signals / radar_axes / connections /
     * reasoning_trail / reasoning_steps / langfuse_trace_id / follow_up_questions /
     * confidence / sources_used / peer_ids / provenance / warning).
     */
    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> runMixer(
            @RequestBody(required = false) Map<String, Object> request) {
        Map<String, Object> body = request != null ? request : Map.of();
        List<String> cardIds = parseCardIds(body);
        if (cardIds.isEmpty()) {
            log.info("Mixer | card_ids 미지정 — fixture stub 반환");
            return ResponseEntity.ok(ApiResponse.success(fixture.mixerResult(cardIds)));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> ratios = body.get("ratios") instanceof Map<?, ?> r
                ? (Map<String, Object>) r
                : Map.of();
        String userContext = body.get("user_context") instanceof String s ? s : null;

        try {
            Map<String, Object> result = aiClientService.runMixer(cardIds, ratios, userContext).block();
            if (result == null) {
                log.warn("Mixer | axis-ai 응답 null — fixture fallback");
                return ResponseEntity.ok(ApiResponse.success(fixture.mixerResult(cardIds)));
            }
            log.info("Mixer | cards={} confidence={}", cardIds.size(), result.get("confidence"));
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (AiServerException e) {
            log.warn("Mixer | axis-ai 호출 실패 — fixture fallback | {}", e.getMessage());
            return ResponseEntity.ok(ApiResponse.success(fixture.mixerResult(cardIds)));
        }
    }

    @PostMapping("/{mixId}/share")
    public ResponseEntity<ApiResponse<Map<String, Object>>> shareMixerResult(@PathVariable String mixId) {
        return ResponseEntity.ok(ApiResponse.success(fixture.shareMixerResult(mixId)));
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
