/*
 * 작성일: 2026-04-21
 * 작성자: 최종민
 * 변경이력:
 *   2026-04-21 최종민 — axis-backend 베이스라인 구축 시 검색 요청 DTO 추가, 이후 검색 화면 CARD_NEWS를 AI 의미 검색(Qdrant)으로 위임
 */
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
