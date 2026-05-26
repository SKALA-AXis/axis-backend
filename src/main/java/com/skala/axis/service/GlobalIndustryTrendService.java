package com.skala.axis.service;

import com.skala.axis.domain.GlobalIndustryTrend;
import com.skala.axis.dto.GlobalIndustryTrendResponse;
import com.skala.axis.repository.GlobalIndustryTrendRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@code global_industry_trends} read 서비스.
 *
 * <p>axis-ai 의 GlobalTrendsAgent (5-phase) 가 cron 으로 daily upsert 한 DB row 를
 * frontend 가 진입 시 GET 으로 즉시 받음. POST /trends/run (sync proxy, ₩600~1,000/회)
 * 대신 cron 결과를 캐싱처럼 사용해 비용 절감.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GlobalIndustryTrendService {

    private final GlobalIndustryTrendRepository repository;

    /**
     * trend_date 구간 조회 (default: 최근 30 일).
     */
    public List<GlobalIndustryTrendResponse> getInRange(LocalDate from, LocalDate to, int limit) {
        LocalDate effectiveTo = to != null ? to : LocalDate.now();
        LocalDate effectiveFrom = from != null ? from : effectiveTo.minusDays(30);
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return repository.findInRange(effectiveFrom, effectiveTo, PageRequest.of(0, safeLimit))
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * 가장 최근 trend_date 한 batch.
     */
    public List<GlobalIndustryTrendResponse> getLatest(int limit) {
        LocalDate latest = repository.findLatestTrendDate();
        if (latest == null) {
            log.info("GlobalIndustryTrends | DB 비어있음 (latest=null)");
            return List.of();
        }
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return repository.findByTrendDate(latest).stream()
                .limit(safeLimit)
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * 한 axis-ai 분석 호출 (source_analysis_id) 의 모든 row.
     */
    public List<GlobalIndustryTrendResponse> getBySourceAnalysisId(String sourceAnalysisId) {
        if (sourceAnalysisId == null || sourceAnalysisId.isBlank()) return List.of();
        return repository.findBySourceAnalysisId(sourceAnalysisId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private GlobalIndustryTrendResponse toResponse(GlobalIndustryTrend t) {
        return GlobalIndustryTrendResponse.builder()
                .id(t.getId())
                .trendDate(t.getTrendDate())
                .industry(t.getIndustry())
                .region(t.getRegion())
                .keyword(t.getKeyword())
                .keywordCategory(t.getKeywordCategory())
                .title(t.getTitle())
                .summary(t.getSummary())
                .mentionCount(t.getMentionCount())
                .impactScore(t.getImpactScore())
                .confidence(t.getConfidence())
                .relatedPeerIds(arrayToList(t.getRelatedPeerIds()))
                .relatedCardIds(arrayToList(t.getRelatedCardIds()))
                .sourceRawArticleIds(arrayToList(t.getSourceRawArticleIds()))
                .skAxImplication(t.getSkAxImplication())
                .payload(t.getPayload() != null ? t.getPayload() : Map.of())
                .sourceAnalysisId(t.getSourceAnalysisId())
                .createdAt(t.getCreatedAt())
                .updatedAt(t.getUpdatedAt())
                .build();
    }

    private <T> List<T> arrayToList(T[] arr) {
        return arr == null ? List.of() : Arrays.asList(arr);
    }
}
