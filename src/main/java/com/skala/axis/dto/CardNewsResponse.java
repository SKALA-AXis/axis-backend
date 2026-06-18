/*
 * 작성일: 2026-05-12
 * 작성자: 최종민
 * 변경이력:
 *   2026-05-12 최종민 — issue_cards→card_news 리네이밍 및 image*→cover* 필드 OpenAPI 계약 정렬, 카드 라인 배지(peer·sector·score) 추가
 *   2026-05-18 박지원 — 원문 신뢰도(credibility) 필드 제거, 소스 카운트 응답 수정, 발행 시각순 카드 정렬
 *   2026-05-19 박진 — DB 수정 및 미사용 기능 페이지 삭제 반영
 *   2026-05-27 안가은 — 카드뉴스 데이터 연동
 *   2026-06-14 심유정 — 카드뉴스 메인 상세 필드 노출 및 전략 액션 projection 추가
 */
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

    @JsonProperty("published_at")
    private String publishedAt;

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

    @JsonProperty("insight_details")
    private List<Map<String, Object>> insightDetails;

    @JsonProperty("action_details")
    private List<Map<String, Object>> actionDetails;

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

    @JsonProperty("strategy_context_applied")
    private Boolean strategyContextApplied;

    @JsonProperty("strategy_context_applied_at")
    private String strategyContextAppliedAt;
}
