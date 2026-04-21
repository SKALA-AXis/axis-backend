package com.skala.axis.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SlackService {
    @Value("${slack.webhook-url:}")
    private String webhookUrl;

    public void sendMessage(String text) {
        if (webhookUrl.isBlank()) {
            log.warn("Slack webhook URL 미설정. 메시지 전송 스킵: {}", text);
            return;
        }
        WebClient.create().post()
                .uri(webhookUrl)
                .bodyValue(Map.of("text", text))
                .retrieve()
                .bodyToMono(Void.class)
                .doOnError(e -> log.error("Slack 전송 실패: {}", e.getMessage()))
                .subscribe();
    }
}
