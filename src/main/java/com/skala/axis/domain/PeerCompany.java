/*
 * 작성일: 2026-04-21
 * 작성자: 최종민
 * 변경이력:
 *   2026-04-21 최종민 — axis-backend 베이스라인으로 엔티티 추가
 *   2026-05-07 박지원 — company tier 필드 추가
 *   2026-05-19 박진 — DB 수정 및 미사용 기능 페이지 관련 정리
 */
package com.skala.axis.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "peer_companies")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PeerCompany {
    @Id
    @Column(length = 50)
    private String id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 20)
    private String tier;

    @Column
    private String[] keywords;

    @Column(name = "dart_period")
    private String dartPeriod;

    @Column(name = "dart_revenue_krwbn")
    private BigDecimal dartRevenueKrwbn;

    @Column(name = "dart_operating_profit_krwbn")
    private BigDecimal dartOperatingProfitKrwbn;

    @Column(name = "dart_operating_margin_pct")
    private BigDecimal dartOperatingMarginPct;

    @Column(name = "dart_revenue_growth_pct")
    private BigDecimal dartRevenueGrowthPct;

    @Column(name = "dart_operating_profit_growth_pct")
    private BigDecimal dartOperatingProfitGrowthPct;

    @Column(name = "ax_revenue_share_pct")
    private BigDecimal axRevenueSharePct;

    @Column(name = "contract_count")
    private Integer contractCount;

    @Column(name = "core_keywords")
    private String[] coreKeywords;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "peer_plus_payload", columnDefinition = "jsonb")
    private Map<String, Object> peerPlusPayload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "financial_history", columnDefinition = "jsonb")
    private List<Map<String, Object>> financialHistory;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "job_posting_history", columnDefinition = "jsonb")
    private List<Map<String, Object>> jobPostingHistory;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "legacy_payload", columnDefinition = "jsonb")
    private Map<String, Object> legacyPayload;

    @Column(name = "financial_updated_at")
    private LocalDateTime financialUpdatedAt;

    @Column(name = "is_active")
    private Boolean isActive;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
