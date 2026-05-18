package com.skala.axis.dto;

import lombok.Builder;
import lombok.Getter;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class CardNewsResponse {
    private String id;
    private String peerId;
    private Long clusterId;
    private String title;
    private String eventType;
    private String sector;
    private List<String> sectors;
    private String exposureBand;
    private Float exposureScore;
    private String importance;
    private Float importanceScore;
    private LocalDateTime createdAt;

    /**
     * 카드 3줄 요약 — 1: WHO + WHAT, 2: WHEN + WHERE / HOW, 3: 핵심 수치 / 차별점.
     * 일일 브리핑 이메일 + frontend 카드 상세에서 표시.
     */
    private List<String> summaryLines;

    /**
     * 카드의 대표 source URL — sources[0] (가장 높은 credibility). 이메일에서 카드 클릭 시 원문 이동용.
     */
    private String primarySourceUrl;

    /**
     * 카드 대표 이미지 URL (예: "/api/images/123"). 크롤링된 이미지가 없으면 null —
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
}
