package com.skala.axis.repository;

import com.skala.axis.domain.GlobalIndustryTrend;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface GlobalIndustryTrendRepository extends JpaRepository<GlobalIndustryTrend, UUID> {

    /**
     * trend_date 구간 + 영향도(impact_score) 우선 정렬.
     * impact_score 가 null 인 row 는 mention_count 로 떨어진다 (NULLS LAST 효과).
     */
    @Query("""
        SELECT t FROM GlobalIndustryTrend t
        WHERE t.trendDate BETWEEN :from AND :to
        ORDER BY t.trendDate DESC,
                 t.impactScore DESC NULLS LAST,
                 t.mentionCount DESC
        """)
    List<GlobalIndustryTrend> findInRange(
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            Pageable pageable);

    /**
     * 가장 최근 trend_date 1 개를 찾는다. cron 이 daily 1 회 batch upsert 하므로
     * 보통 "오늘 한 batch" 를 의미. 없으면 null 반환.
     */
    @Query("SELECT MAX(t.trendDate) FROM GlobalIndustryTrend t")
    LocalDate findLatestTrendDate();

    /**
     * 특정 trend_date 한 batch 전체.
     */
    @Query("""
        SELECT t FROM GlobalIndustryTrend t
        WHERE t.trendDate = :date
        ORDER BY t.impactScore DESC NULLS LAST,
                 t.mentionCount DESC
        """)
    List<GlobalIndustryTrend> findByTrendDate(@Param("date") LocalDate date);

    /**
     * source_analysis_id 단위 조회 — 한 GlobalTrendsAgent.generate() 호출이 만든
     * 모든 row. 디버깅 / Langfuse trace 매칭용.
     */
    @Query("""
        SELECT t FROM GlobalIndustryTrend t
        WHERE t.sourceAnalysisId = :sourceAnalysisId
        ORDER BY t.impactScore DESC NULLS LAST,
                 t.mentionCount DESC
        """)
    List<GlobalIndustryTrend> findBySourceAnalysisId(
            @Param("sourceAnalysisId") String sourceAnalysisId);
}
