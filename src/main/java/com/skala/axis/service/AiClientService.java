package com.skala.axis.service;

import com.skala.axis.dto.BriefingContent;
import com.skala.axis.dto.CardNewsResponse;
import com.skala.axis.dto.SearchRequest;
import com.skala.axis.dto.SearchResponse;
import com.skala.axis.exception.AiServerException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiClientService {
    private final WebClient aiWebClient;
    private static final org.springframework.core.ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new org.springframework.core.ParameterizedTypeReference<>() {};
    private static final Pattern ERROR_CODE_PATTERN =
            Pattern.compile("\"code\"\\s*:\\s*\"([A-Za-z0-9_\\-]+)\"");

    public Mono<Map<String, Object>> getRaw(String uri, Duration timeout) {
        return aiWebClient.get()
                .uri(uri)
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .timeout(timeout)
                .onErrorResume(e -> rawAiError(uri, e));
    }

    public Mono<Map<String, Object>> postRaw(String uri, Object request, Duration timeout) {
        Object body = request == null ? Map.of() : request;
        return aiWebClient.post()
                .uri(uri)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .timeout(timeout)
                .onErrorResume(e -> rawAiError(uri, e));
    }

    private Mono<Map<String, Object>> rawAiError(String uri, Throwable e) {
        AiServerException error = toAiServerException(operationFromUri(uri), uri, e);
        log.warn("axis-ai raw 호출 실패 | uri={} code={} error={}", uri, error.getCode(), error.getMessage());
        return Mono.error(error);
    }

    /** 단일 기사 분류 — axis-ai {@code /classify}(운영 분류기 재사용). 데모 인젝트가 사용. */
    public Mono<Map<String, Object>> classifyArticle(String title, String content, String company) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", title == null ? "" : title);
        body.put("content", content == null ? "" : content);
        body.put("company", company == null ? "" : company);
        return postRaw("/classify", body, Duration.ofSeconds(90));
    }

    public Mono<SearchResponse> search(SearchRequest request) {
        return aiWebClient.post()
                .uri("/search")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(SearchResponse.class)
                .timeout(Duration.ofSeconds(10))
                .onErrorResume(e -> aiError("SEARCH", "/search", e));
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
                .onErrorResume(e -> aiError("BRIEFING_DELIVERY", "/pipeline/delivery", e));
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
                .onErrorResume(e -> aiError("INSIGHT", "/insight/generate", e));
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
     * @param analysisMode 빠른 실행(quick) 또는 정확 분석(deep)
     */
    public Mono<Map<String, Object>> runMixer(
            List<String> cardIds,
            Map<String, Object> ratios,
            String userContext,
            String analysisMode,
            UUID userId) {
        String normalizedMode = normalizeMixerAnalysisMode(analysisMode);
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("card_ids", cardIds);
        body.put("analysis_mode", normalizedMode);
        if (userId != null) {
            body.put("user_id", userId.toString());
        }
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
                .timeout(mixerTimeout(normalizedMode, false))
                .onErrorResume(e -> aiError("MIXER", "/mixer/analyze", e));
    }

    /**
     * MixerAnalysis SSE 스트리밍 — axis-ai {@code /mixer/analyze/stream} 위임.
     *
     * <p>각 element 는 axis-ai 가 emit 한 SSE {@code data:} JSON 문자열
     * ({@code {"type":"stage"|"result"|"error", ...}}). 메인 LLM 단계 사이 간격이
     * 길 수 있어 모드별 timeout 을 둔다.</p>
     */
    public Flux<String> runMixerStream(
            List<String> cardIds,
            Map<String, Object> ratios,
            String userContext,
            String analysisMode,
            UUID userId) {
        String normalizedMode = normalizeMixerAnalysisMode(analysisMode);
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("card_ids", cardIds);
        body.put("analysis_mode", normalizedMode);
        if (userId != null) {
            body.put("user_id", userId.toString());
        }
        if (ratios != null && !ratios.isEmpty()) {
            body.put("ratios", ratios);
        }
        if (userContext != null && !userContext.isBlank()) {
            body.put("user_context", userContext);
        }
        return aiWebClient.post()
                .uri("/mixer/analyze/stream")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(mixerTimeout(normalizedMode, true))
                .onErrorResume(e -> {
                    AiServerException error = toAiServerException(
                            "MIXER_STREAM",
                            "/mixer/analyze/stream",
                            e
                    );
                    log.warn("axis-ai /mixer/analyze/stream 호출 실패: code={} error={}",
                            error.getCode(), error.getMessage());
                    return Flux.just(mixerStreamError(error));
                });
    }

    private static String normalizeMixerAnalysisMode(String analysisMode) {
        if (analysisMode != null && "deep".equals(analysisMode.trim().toLowerCase(Locale.ROOT))) {
            return "deep";
        }
        return "quick";
    }

    private static Duration mixerTimeout(String analysisMode, boolean stream) {
        boolean deep = "deep".equals(normalizeMixerAnalysisMode(analysisMode));
        if (stream) {
            return Duration.ofSeconds(deep ? 150 : 45);
        }
        return Duration.ofSeconds(deep ? 120 : 45);
    }

    private static String mixerStreamError(AiServerException error) {
        String code = sanitizeJsonString(error.getCode());
        return "{\"type\":\"error\",\"message\":\""
                + AiServerException.CALL_FAILED_MESSAGE
                + "\",\"error_code\":\""
                + code
                + "\"}";
    }

    /**
     * Home dashboard Today's Insight — axis-ai 의 {@code /today-insight/generate} 위임.
     *
     * <p>통합 이슈, 카드뉴스, profile context, prior today_insight_reports JSON memory 를
     * 함께 사용하는 executive-facing 홈 인사이트. LLM 단일 호출 + DB lookup 이므로 timeout 90초.</p>
     */
    public Mono<Map<String, Object>> generateTodayInsight(Map<String, Object> request) {
        Map<String, Object> body = request == null ? Map.of() : request;
        return postRaw("/today-insight/generate", body, Duration.ofSeconds(90));
    }

    public Mono<Map<String, Object>> summarizeUserStrategyOverlay(
            String rawText,
            String title,
            java.util.UUID userId,
            Map<String, Object> metadata
    ) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("raw_text", rawText);
        body.put("title", title);
        body.put("user_id", userId == null ? null : userId.toString());
        body.put("metadata", metadata == null ? Map.of() : metadata);
        return postRaw("/profile/user-skax-overlay", body, Duration.ofSeconds(120));
    }

    public Mono<Map<String, Object>> ocrUserStrategyFile(
            String fileName,
            String contentType,
            byte[] fileBytes
    ) {
        Map<String, Object> body = new HashMap<>();
        body.put("file_name", fileName);
        body.put("content_type", contentType);
        body.put("file_base64", Base64.getEncoder().encodeToString(fileBytes == null ? new byte[0] : fileBytes));
        return postRaw("/profile/user-strategy-file-ocr", body, Duration.ofSeconds(180));
    }

    public Mono<Map<String, Object>> regenerateCardNewsStrategyContext(
            String cardNewsId,
            Map<String, Object> analysisPackage,
            java.util.UUID userId
    ) {
        Map<String, Object> body = new HashMap<>();
        body.put("card_news_id", cardNewsId);
        body.put("analysis_package", analysisPackage == null ? Map.of() : analysisPackage);
        if (userId != null) {
            body.put("user_id", userId.toString());
        }
        return postRaw("/card-news/strategy-context/regenerate", body, Duration.ofSeconds(180));
    }

    /**
     * BriefingGenerationAgent — axis-ai {@code /briefing/generate} 위임.
     *
     * <p>ContextPackAssembler 기반 기간 브리핑. LLM + DB lookup 이므로 timeout 120초.</p>
     */
    public Mono<Map<String, Object>> generateBriefing(Map<String, Object> request) {
        Map<String, Object> body = request == null ? Map.of() : request;
        return postRaw("/briefing/generate", body, Duration.ofSeconds(120));
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
                .onErrorResume(e -> aiError("PEER", "/peer/compare", e));
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
                .onErrorResume(e -> aiError("GLOBAL_TRENDS", "/global/trends/run", e));
    }

    /**
     * LinkVerification — axis-ai 의 {@code /link/verify} 위임.
     *
     * <p>HTTP HEAD/GET 기반 deterministic 검증 — LLM 미사용. 카드 source 수 N에 비례 (병렬).
     * timeout 20초 (대량 sources + GET hash 검증 포함).</p>
     *
     * @param cardId 검증 대상 카드 id
     */
    public Mono<Map<String, Object>> verifyLink(String cardId) {
        Map<String, Object> body = Map.of("card_id", cardId);
        return aiWebClient.post()
                .uri("/link/verify")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofSeconds(20))
                .onErrorResume(e -> aiError("LINK_VERIFY", "/link/verify", e));
    }

    /**
     * ChatOrchestrator — axis-ai 의 {@code /chat} 위임.
     *
     * <p>intent 분류 + 분석 agent 라우팅 + compose. sub-agent 호출이 일어나면 LLM 응답
     * 시간 더해져 ~60s. timeout 90초.</p>
     *
     * @param message 사용자 메시지
     * @param sessionId 세션 ID (null 시 axis-ai 가 생성)
     * @param history 최근 대화 turn 들
     */
    public Mono<Map<String, Object>> chat(
            String message, String sessionId, List<Map<String, Object>> history) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("message", message);
        if (sessionId != null && !sessionId.isBlank()) {
            body.put("session_id", sessionId);
        }
        if (history != null && !history.isEmpty()) {
            body.put("history", history);
        }
        return chat(body);
    }

    /**
     * ChatOrchestrator raw contract forwarder.
     *
     * <p>Preserves conversation_id, current_page, client_context, and future
     * assistant fields so the backend does not strip page-aware CAG context.</p>
     */
    public Mono<Map<String, Object>> chat(Map<String, Object> request) {
        Map<String, Object> body = request == null ? new java.util.HashMap<>() : request;
        return aiWebClient.post()
                .uri("/chat")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofSeconds(90))
                .onErrorResume(e -> aiError("CHAT", "/chat", e));
    }

    public Mono<Map<String, Object>> chatPdf(Map<String, Object> request, MultipartFile file) {
        Map<String, Object> body = new HashMap<>();
        body.put("request", request == null ? Map.of() : request);
        body.put("file_name", file.getOriginalFilename() == null ? "attachment.pdf" : file.getOriginalFilename());
        body.put("content_type", file.getContentType() == null ? "application/pdf" : file.getContentType());
        try {
            body.put("pdf_base64", Base64.getEncoder().encodeToString(file.getBytes()));
        } catch (IOException e) {
            return Mono.error(new AiServerException(
                    "CHAT_PDF_FILE_READ_FAILED",
                    "PDF 파일을 읽지 못했습니다.",
                    HttpStatus.BAD_REQUEST
            ));
        }

        return aiWebClient.post()
                .uri("/chat/pdf")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(MAP_TYPE)
                .timeout(Duration.ofSeconds(90))
                .onErrorResume(e -> aiError("CHAT_PDF", "/chat/pdf", e));
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

    private <T> Mono<T> aiError(String operation, String uri, Throwable e) {
        AiServerException error = toAiServerException(operation, uri, e);
        log.warn("axis-ai 호출 실패 | operation={} uri={} code={} error={}",
                operation, uri, error.getCode(), error.getMessage());
        return Mono.error(error);
    }

    private AiServerException toAiServerException(String operation, String uri, Throwable e) {
        if (e instanceof AiServerException aiServerException) {
            return aiServerException;
        }

        String prefix = normalizeErrorPrefix(operation);
        if (e instanceof TimeoutException) {
            return new AiServerException(
                    prefix + "_AI_TIMEOUT",
                    "axis-ai 호출 타임아웃: " + uri,
                    HttpStatus.GATEWAY_TIMEOUT
            );
        }
        if (e instanceof WebClientResponseException responseException) {
            String responseBody = responseException.getResponseBodyAsString();
            int statusCode = responseException.getRawStatusCode();
            String upstreamCode = extractUpstreamErrorCode(responseBody);
            String message = "axis-ai HTTP " + statusCode + " " + uri
                    + (responseBody.isBlank() ? "" : " | " + responseBody);
            return new AiServerException(
                    upstreamCode.isBlank() ? prefix + "_AI_HTTP_" + statusCode : upstreamCode,
                    message,
                    HttpStatus.BAD_GATEWAY
            );
        }
        if (e instanceof WebClientRequestException) {
            return new AiServerException(
                    prefix + "_AI_CONNECTION_FAILED",
                    "axis-ai 연결 실패: " + uri + " | " + nullSafeMessage(e),
                    HttpStatus.BAD_GATEWAY
            );
        }
        return new AiServerException(
                prefix + "_AI_CALL_FAILED",
                "axis-ai 호출 실패: " + uri + " | " + nullSafeMessage(e),
                HttpStatus.BAD_GATEWAY
        );
    }

    private static String operationFromUri(String uri) {
        String path = uri == null ? "" : uri.toLowerCase(Locale.ROOT);
        if (path.contains("/mixer/analyze/stream")) return "MIXER_STREAM";
        if (path.contains("/mixer")) return "MIXER";
        if (path.contains("/insight")) return "INSIGHT";
        if (path.contains("/global/trends")) return "GLOBAL_TRENDS";
        if (path.contains("/briefing/generate")) return "BRIEFING";
        if (path.contains("/today-insight")) return "TODAY_INSIGHT";
        if (path.contains("/link/verify")) return "LINK_VERIFY";
        if (path.contains("/peer/compare")) return "PEER";
        if (path.contains("/chat/pdf")) return "CHAT_PDF";
        if (path.contains("/chat")) return "CHAT";
        if (path.contains("/pipeline")) return "PIPELINE";
        if (path.contains("/health")) return "HEALTH";
        return "AXIS_AI";
    }

    private static String normalizeErrorPrefix(String operation) {
        String raw = operation == null || operation.isBlank() ? "AXIS_AI" : operation;
        return raw.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    }

    private static String nullSafeMessage(Throwable e) {
        return e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName()
                : e.getMessage();
    }

    private static String sanitizeJsonString(String raw) {
        return (raw == null || raw.isBlank() ? "AI_CALL_FAILED" : raw)
                .replace("\\", " ")
                .replace("\"", "'")
                .replace("\n", " ")
                .replace("\r", " ");
    }

    private static String extractUpstreamErrorCode(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "";
        }
        Matcher matcher = ERROR_CODE_PATTERN.matcher(responseBody);
        if (!matcher.find()) {
            return "";
        }
        return normalizeErrorPrefix(matcher.group(1));
    }
}
