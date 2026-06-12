package com.skala.axis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * axis-ai POST /search 요청 — 계약: axis-infra/api/ai-internal-api.yaml (hybridSearch).
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class SearchRequest {
    @NotBlank
    private String query;

    private String company;

    @JsonProperty("event_type")
    private String eventType;

    @JsonProperty("top_k")
    private Integer topK = 10;
}
