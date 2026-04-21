package com.skala.axis.service;

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

    public Mono<Void> triggerPipeline(List<String> peerIds) {
        return aiWebClient.post()
                .uri("/pipeline/run")
                .bodyValue(Map.of("peer_ids", peerIds, "trigger_type", "scheduled"))
                .retrieve()
                .bodyToMono(Void.class)
                .timeout(Duration.ofSeconds(5))
                .onErrorResume(e -> {
                    log.warn("파이프라인 트리거 실패 (비동기 무시): {}", e.getMessage());
                    return Mono.empty();
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
