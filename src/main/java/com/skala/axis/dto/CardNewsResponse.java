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

    /** 카드 대표 이미지 URL (예: "/api/images/123"). 이미지 없으면 null. */
    private String imageUrl;

    /** 이미지 출처 표기 (예: "제공: 한경"). null 이면 미표기. */
    private String imageAttribution;

    /** 이미지 alt 텍스트 (접근성 + 이메일 클라이언트가 이미지 차단 시 노출). */
    private String imageAlt;
}
