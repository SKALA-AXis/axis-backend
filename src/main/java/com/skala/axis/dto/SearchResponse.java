/*
 * 작성일: 2026-04-21
 * 작성자: 최종민
 * 변경이력:
 *   2026-04-21 최종민 — axis-backend 베이스라인 구축 시 검색 응답 DTO 추가, 이후 검색 화면 CARD_NEWS를 AI 의미 검색(Qdrant)으로 위임
 *   2026-05-18 박지원 — 원문 신뢰도(credibility) 필드 제거
 */
package com.skala.axis.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * axis-ai POST /search 응답 — 계약: axis-infra/api/ai-internal-api.yaml (SearchResponse/SearchHit).
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SearchResponse {
    private List<Hit> hits = List.of();
    private Integer total;

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Hit {
        @JsonProperty("rdb_id")
        private Long rdbId;

        private String company;
        private String title;
        private String summary;
        private String importance;

        @JsonProperty("event_type")
        private String eventType;

        @JsonProperty("pub_date")
        private String pubDate;

        @JsonProperty("rerank_score")
        private Double rerankScore;

        @JsonProperty("source_url")
        private String sourceUrl;
    }
}
