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
