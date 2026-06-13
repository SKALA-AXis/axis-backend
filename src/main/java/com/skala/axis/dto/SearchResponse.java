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
