package com.skala.axis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Getter
@Builder
public class CardNewsResponse {
    private String id;

    @JsonProperty("peer_id")
    private String peerId;

    @JsonProperty("cluster_id")
    private Long clusterId;

    private String title;

    private String subtitle;

    private String category;

    private String date;

    @JsonProperty("event_type")
    private String eventType;

    private String sector;

    private List<String> sectors;

    @JsonProperty("category_label")
    private String categoryLabel;

    @JsonProperty("exposure_band")
    private String exposureBand;

    @JsonProperty("exposure_score")
    private Float exposureScore;

    @JsonProperty("trust_score")
    private Float trustScore;

    private String primaryKeywordCategory;

    private List<String> keywords;

    private List<Object> keywordCategories;

    private Map<String, Object> keywordFrequency;

    private String importance;

    private Float importanceScore;

    @JsonProperty("published_date")
    private String publishedDate;

    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    /**
     * 카드 3줄 요약 — 1: WHO + WHAT, 2: WHEN + WHERE / HOW, 3: 핵심 수치 / 차별점.
     * 일일 브리핑 이메일 + frontend 카드 상세에서 표시.
     */
    @JsonProperty("summary_lines")
    private List<String> summaryLines;

    private List<String> summary;

    /**
     * 카드의 대표 source URL — sources[0]. 이메일에서 카드 클릭 시 원문 이동용.
     */
    private String source;

    private String sourceUrl;

    /**
     * 카드 대표 이미지 URL. 크롤링된 이미지가 없으면 null —
     * frontend 가 peer 로고로 fallback.
     *
     * <p>필드명은 OpenAPI 계약 (axis-infra/api/openapi.yaml CardNewsResponse) 의
     * coverImageUrl 과 정렬. frontend 가 직접 읽는 키.
     */
    private String coverImageUrl;

    /** 이미지 출처 표기 (예: "제공: 한경"). null 이면 미표기. */
    private String coverImageAttribution;

    /** 이미지 alt 텍스트 (접근성 + 이메일 클라이언트가 이미지 차단 시 노출). */
    private String coverImageAlt;

    private String detailDescription;

    private List<String> detailPoints;

    private List<String> insights;

    private List<String> actionItems;

    private Map<String, Object> implication;

    private List<Map<String, Object>> sources;

    @JsonProperty("source_raw_article_ids")
    private List<Long> sourceRawArticleIds;

    @JsonProperty("source_count")
    private Integer sourceCount;

    @JsonProperty("validation_pass")
    private Boolean validationPass;

    @JsonProperty("is_human_reviewed")
    private Boolean isHumanReviewed;
}
