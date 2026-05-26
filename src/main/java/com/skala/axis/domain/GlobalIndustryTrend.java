package com.skala.axis.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * {@code global_industry_trends} 테이블 매핑.
 *
 * <p>axis-ai 의 GlobalTrendsAgent (5-phase) 가 일 단위로 upsert. unique
 * constraint {@code (trend_date, industry, region, keyword)} 으로 idempotent.
 * payload 안에 phase-별 raw output (snapshot / detection / impact / forecast)
 * 가 들어있어 frontend 가 detail view 에서 그대로 표시.</p>
 *
 * <p>migration: V29__front_product_read_models_and_analysis_tables.sql (L326),
 * V30 (source_analysis_id 추가).</p>
 */
@Entity
@Table(name = "global_industry_trends")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GlobalIndustryTrend {

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "trend_date", nullable = false)
    private LocalDate trendDate;

    @Column(name = "industry", nullable = false, length = 100)
    private String industry;

    @Column(name = "region", nullable = false, length = 50)
    private String region;

    @Column(name = "keyword", nullable = false, length = 120)
    private String keyword;

    @Column(name = "keyword_category", length = 80)
    private String keywordCategory;

    @Column(name = "title")
    private String title;

    @Column(name = "summary", columnDefinition = "text")
    private String summary;

    @Column(name = "mention_count", nullable = false)
    private Integer mentionCount;

    @Column(name = "impact_score", precision = 5, scale = 2)
    private BigDecimal impactScore;

    @Column(name = "confidence", precision = 4, scale = 3)
    private BigDecimal confidence;

    @Column(name = "related_peer_ids")
    private String[] relatedPeerIds;

    @Column(name = "related_card_ids")
    private String[] relatedCardIds;

    @Column(name = "source_raw_article_ids")
    private Long[] sourceRawArticleIds;

    @Column(name = "sk_ax_implication", columnDefinition = "text")
    private String skAxImplication;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "source_analysis_id", length = 100)
    private String sourceAnalysisId;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
