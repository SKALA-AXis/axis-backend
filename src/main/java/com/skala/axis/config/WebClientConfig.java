package com.skala.axis.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {
    @Value("${ai.server.base-url:http://localhost:8001}")
    private String aiServerBaseUrl;

    @Bean
    public WebClient aiWebClient() {
        return WebClient.builder()
                .baseUrl(aiServerBaseUrl)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}
