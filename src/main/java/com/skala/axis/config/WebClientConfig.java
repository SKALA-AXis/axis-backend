/*
 * 작성일: 2026-04-21
 * 작성자: 최종민
 * 변경이력:
 *   2026-04-21 최종민 — axis-backend 베이스라인 작성
 *   2026-06-12 박진 — 생성형 브리핑 워크플로 연동 위해 WebClient 설정 보완
 */
package com.skala.axis.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {
    private static final int AXIS_AI_MAX_IN_MEMORY_BYTES = 8 * 1024 * 1024;

    @Value("${ai.server.base-url:http://localhost:8001}")
    private String aiServerBaseUrl;

    @Bean
    public WebClient aiWebClient() {
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs()
                        .maxInMemorySize(AXIS_AI_MAX_IN_MEMORY_BYTES))
                .build();
        return WebClient.builder()
                .baseUrl(aiServerBaseUrl)
                .exchangeStrategies(strategies)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}
