package com.skala.axis.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * axis-ai 의 {@code POST /pipeline/delivery} 응답 — 이메일 본문 데이터.
 *
 * <p>axis-backend 의 {@code SesMailService} 가 받아 AWS SES V2 SDK 로 발송한다.
 * 자세한 흐름: {@code axis-infra/docs/SES_INTEGRATION.md}.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BriefingContent(
        String subject,
        String html,
        String text,
        @JsonProperty("recipients") List<String> recipients
) {}
