package com.skala.axis.service;

import com.skala.axis.dto.BriefingContent;
import com.skala.axis.dto.CardNewsResponse;
import com.skala.axis.dto.SearchRequest;
import com.skala.axis.dto.SearchResponse;
import com.skala.axis.exception.AiServerException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiClientService {
    private final WebClient aiWebClient;

    public Mono<SearchResponse> search(SearchRequest request) {
        return aiWebClient.post()
                .uri("/search")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(SearchResponse.class)
                .timeout(Duration.ofSeconds(10))
                .onErrorMap(TimeoutException.class, ex -> new AiServerException("검색 타임아웃"))
                .onErrorResume(e -> {
                    log.warn("AI 서버 검색 실패: {}", e.getMessage());
                    return Mono.error(new AiServerException(e.getMessage()));
                });
    }

    public Mono<Void> triggerPipeline(String track, List<String> peerIds) {
        return aiWebClient.post()
                .uri("/pipeline/run")
                .bodyValue(Map.of(
                        "track", track,
                        "company", peerIds,
                        "trigger_type", "scheduled"
                ))
                .retrieve()
                .bodyToMono(Map.class)
                .then()
                .timeout(Duration.ofSeconds(5))
                .onErrorResume(e -> {
                    log.warn("파이프라인 트리거 실패 (비동기 무시): track={} error={}", track, e.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * 일일 브리핑 본문 데이터 요청 — axis-ai 가 cards 받아 HTML/text 본문 빌더 후 반환.
     * SES 발송은 backend 의 SesMailService 가 담당 (axis-ai 는 발송 안 함).
     */
    public Mono<BriefingContent> buildBriefing(List<CardNewsResponse> cards) {
        return aiWebClient.post()
                .uri("/pipeline/delivery")
                .bodyValue(Map.of("cards", cards))
                .retrieve()
                .bodyToMono(BriefingContent.class)
                .timeout(Duration.ofSeconds(30))
                .onErrorMap(TimeoutException.class, ex -> new AiServerException("브리핑 빌더 타임아웃"))
                .onErrorResume(e -> {
                    log.warn("axis-ai /pipeline/delivery 호출 실패: {}", e.getMessage());
                    return Mono.error(new AiServerException(e.getMessage()));
                });
    }

    /**
     * InsightCascade 4-phase 분석 — axis-ai 의 {@code /insight/generate} 위임.
     *
     * <p>cold-start fallback prototype (Walking Skeleton Phase 2). LLM gpt-4o 단일 호출이라
     * 응답 ~30초 소요. timeout 60초 (frontend 가 polling 또는 spinner 처리).</p>
     *
     * @param cardIds 분석 대상 카드 id 목록 (2 ≤ N ≤ 10)
     * @param context frontend 가 전달하는 추가 컨텍스트 (옵션)
     */
    public Mono<Map<String, Object>> generateInsight(List<String> cardIds, Map<String, Object> context) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("card_ids", cardIds);
        if (context != null && !context.isEmpty()) {
            body.put("context", context);
        }
        return aiWebClient.post()
                .uri("/insight/generate")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofSeconds(60))
                .onErrorMap(TimeoutException.class, ex -> new AiServerException("Insight 분석 타임아웃 (60s)"))
                .onErrorResume(e -> {
                    log.warn("axis-ai /insight/generate 호출 실패: {}", e.getMessage());
                    return Mono.error(new AiServerException(e.getMessage()));
                });
    }

    /**
     * MixerAnalysis 3-phase 분석 — axis-ai 의 {@code /mixer/analyze} 위임.
     *
     * <p>cold-start fallback prototype (Walking Skeleton Phase 2). LLM gpt-4o 단일 호출이라
     * 응답 ~30초 소요. timeout 60초 (frontend 가 spinner 처리).</p>
     *
     * @param cardIds 분석 대상 카드 id 목록 (2 ≤ N ≤ 20)
     * @param ratios peer / industry / keyword 가중치 (옵션)
     * @param userContext 사용자 자유 입력 (옵션)
     */
    public Mono<Map<String, Object>> runMixer(
            List<String> cardIds, Map<String, Object> ratios, String userContext) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("card_ids", cardIds);
        if (ratios != null && !ratios.isEmpty()) {
            body.put("ratios", ratios);
        }
        if (userContext != null && !userContext.isBlank()) {
            body.put("user_context", userContext);
        }
        return aiWebClient.post()
                .uri("/mixer/analyze")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofSeconds(60))
                .onErrorMap(TimeoutException.class, ex -> new AiServerException("Mixer 분석 타임아웃 (60s)"))
                .onErrorResume(e -> {
                    log.warn("axis-ai /mixer/analyze 호출 실패: {}", e.getMessage());
                    return Mono.error(new AiServerException(e.getMessage()));
                });
    }

    /**
     * PeerComparison Phase 1+2+4 분석 — axis-ai 의 {@code /peer/compare} 위임.
     *
     * <p>prototype (Walking Skeleton Phase 2). Phase 3 (Forecast) 는 Day 90+ deferred —
     * 응답의 forecasts 는 빈 배열. timeout 60초.</p>
     *
     * @param peerId 분석 대상 peer id
     * @param windowDays 카드 조회 윈도우 (기본 30일)
     * @param focusSector sector 필터 (옵션)
     */
    public Mono<Map<String, Object>> comparePeer(String peerId, Integer windowDays, String focusSector) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("peer_id", peerId);
        if (windowDays != null) {
            body.put("window_days", windowDays);
        }
        if (focusSector != null && !focusSector.isBlank()) {
            body.put("focus_sector", focusSector);
        }
        return aiWebClient.post()
                .uri("/peer/compare")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofSeconds(60))
                .onErrorMap(TimeoutException.class, ex -> new AiServerException("Peer 분석 타임아웃 (60s)"))
                .onErrorResume(e -> {
                    log.warn("axis-ai /peer/compare 호출 실패: {}", e.getMessage());
                    return Mono.error(new AiServerException(e.getMessage()));
                });
    }

    /**
     * GlobalTrends 5-phase 분석 — axis-ai 의 {@code /global/trends/run} 위임.
     *
     * <p>prototype (Walking Skeleton Phase 2). Phase 1+2 결정적 산식, Phase 3+4+5 LLM 단일
     * 호출. 글로벌 카드 부재 시 graceful 빈 응답 + warning. timeout 90초.</p>
     *
     * @param companyIds 분석 대상 글로벌 회사 ids (null 이면 default 6사)
     * @param focusThemes 특정 theme 필터 (옵션)
     * @param windowDays 분석 윈도우 (기본 30일)
     * @param skAxBusinessLines impact matrix 컬럼 축 (옵션)
     */
    public Mono<Map<String, Object>> runGlobalTrends(
            List<String> companyIds,
            List<String> focusThemes,
            Integer windowDays,
            List<String> skAxBusinessLines) {
        Map<String, Object> body = new java.util.HashMap<>();
        if (companyIds != null && !companyIds.isEmpty()) {
            body.put("company_ids", companyIds);
        }
        if (focusThemes != null && !focusThemes.isEmpty()) {
            body.put("focus_themes", focusThemes);
        }
        if (windowDays != null) {
            body.put("window_days", windowDays);
        }
        if (skAxBusinessLines != null && !skAxBusinessLines.isEmpty()) {
            body.put("sk_ax_business_lines", skAxBusinessLines);
        }
        return aiWebClient.post()
                .uri("/global/trends/run")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofSeconds(90))
                .onErrorMap(TimeoutException.class, ex -> new AiServerException("GlobalTrends 타임아웃 (90s)"))
                .onErrorResume(e -> {
                    log.warn("axis-ai /global/trends/run 호출 실패: {}", e.getMessage());
                    return Mono.error(new AiServerException(e.getMessage()));
                });
    }

    public Mono<Void> triggerWeakSignal() {
        return aiWebClient.post()
                .uri("/weak-signal/run")
                .retrieve()
                .bodyToMono(Void.class)
                .timeout(Duration.ofSeconds(5))
                .onErrorResume(e -> {
                    log.warn("약한 신호 트리거 실패: {}", e.getMessage());
                    return Mono.empty();
                });
    }
}
