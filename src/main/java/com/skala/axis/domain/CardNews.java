/*
 * 작성일: 2026-05-12
 * 작성자: 최종민
 * 변경이력:
 *   2026-05-12 최종민 — issue_cards → card_news 리네임 (DB 스키마 + Java 계층)
 *   2026-05-19 박진 — DB 수정 및 미사용 기능 정리
 *   2026-05-27 안가은 — 카드뉴스 데이터 연동, 소프트삭제·감사로그, 키워드 그래프 API
 *   2026-06-10 심유정 — 카드뉴스 프론트엔드 노출 필드 추가, 카드 전략 액션 프로젝션 추가
 */
package com.skala.axis.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "card_news")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CardNews {
    @Id
    @Column(length = 50)
    private String id;

    @Column(name = "company", nullable = false, length = 50)
    private String peerId;

    @Column(name = "peer_company_id", length = 50)
    private String peerCompanyId;

    @Column(name = "cluster_id")
    private Long clusterId;

    @Column(nullable = false)
    private String title;

    @Column(name = "summary_lines")
    private String[] summaryLines;

    @Column(name = "event_type", length = 50)
    private String eventType;

    @Column(length = 20)
    private String importance;

    @Column(name = "importance_score")
    private Float importanceScore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "implication", columnDefinition = "jsonb")
    private Map<String, Object> implication;

    @Column(name = "primary_keyword_category", length = 80)
    private String primaryKeywordCategory;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "keyword_categories", columnDefinition = "jsonb")
    private Object keywordCategories;

    @Column(name = "keywords")
    private String[] keywords;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "keyword_frequency", columnDefinition = "jsonb")
    private Map<String, Object> keywordFrequency;

    @Column(name = "source_raw_article_ids")
    private Long[] sourceRawArticleIds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sources", columnDefinition = "jsonb")
    private Object sources;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_articles", columnDefinition = "jsonb")
    private Object sourceArticles;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_payload", columnDefinition = "jsonb")
    private Map<String, Object> evidencePayload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "image_assets", columnDefinition = "jsonb")
    private Object imageAssets;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "legacy_payload", columnDefinition = "jsonb")
    private Map<String, Object> legacyPayload;

    @Column(name = "validation_pass")
    private Boolean validationPass;

    @Column(name = "validation_sc_score")
    private Float validationScScore;

    @Column(name = "is_human_reviewed")
    private Boolean isHumanReviewed;

    @Column(name = "primary_raw_article_id")
    private Long primaryRawArticleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CardNewsStatus status;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public CardNewsStatus getStatusOrDefault() {
        return status == null ? CardNewsStatus.ACTIVE : status;
    }

    public void updateStatus(CardNewsStatus status) {
        this.status = status == null ? CardNewsStatus.ACTIVE : status;
    }

}
