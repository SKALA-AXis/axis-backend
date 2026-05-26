package com.skala.axis.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code global_industry_trends} row 한 건의 frontend 응답.
 *
 * <p>{@code payload} 는 phase-별 raw output (snapshot / detection / impact / forecast)
 * 을 그대로 pass-through. frontend 가 detail view 에서 phase 마다 다른 패널을 그린다.</p>
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GlobalIndustryTrendResponse {
    private UUID id;
    private LocalDate trendDate;
    private String industry;
    private String region;
    private String keyword;
    private String keywordCategory;
    private String title;
    private String summary;
    private Integer mentionCount;
    private BigDecimal impactScore;
    private BigDecimal confidence;
    private List<String> relatedPeerIds;
    private List<String> relatedCardIds;
    private List<Long> sourceRawArticleIds;
    private String skAxImplication;
    private Map<String, Object> payload;
    private String sourceAnalysisId;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
