/*
 * 작성일: 2026-06-12
 * 작성자: 최종민
 * 변경이력:
 *   2026-06-12 최종민 — 검색 화면 CARD_NEWS를 AI 의미 검색(Qdrant)으로 위임하는 테스트 작성
 *   2026-06-14 안가은 — 대시보드 및 글로벌 검색 API 보강에 따른 테스트 추가
 */
package com.skala.axis.service;

import com.skala.axis.dto.SearchRequest;
import com.skala.axis.dto.SearchResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GlobalSearchServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private AiClientService aiClientService;

    @InjectMocks
    private GlobalSearchService service;

    @BeforeEach
    void enableSemantic() {
        ReflectionTestUtils.setField(service, "semanticSearchEnabled", true);
    }

    private static SearchResponse aiResponse(long... rdbIds) {
        SearchResponse response = new SearchResponse();
        response.setHits(java.util.Arrays.stream(rdbIds).mapToObj(id -> {
            SearchResponse.Hit hit = new SearchResponse.Hit();
            hit.setRdbId(id);
            hit.setRerankScore(0.9);
            return hit;
        }).toList());
        response.setTotal(rdbIds.length);
        return response;
    }

    private static Map<String, Object> hydratedRow(String cardId, long rdbId, String title) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", cardId);
        row.put("title", title);
        row.put("subtitle", "partnership");
        row.put("company_name", "LG CNS");
        row.put("item_date", "2026-06-10");
        row.put("importance", "high");
        row.put("event_type", "partnership");
        row.put("primary_raw_article_id", rdbId);
        return row;
    }

    private static Map<String, Object> request(String query) {
        return Map.of("query", query, "scopes", List.of("CARD_NEWS"));
    }

    @Test
    @DisplayName("AI 의미 검색 성공 시 hit 순서대로 카드뉴스 아이템을 만들고 ILIKE 경로를 타지 않는다")
    void semanticSearchMapsHitsInRankOrder() {
        when(aiClientService.search(any(SearchRequest.class))).thenReturn(Mono.just(aiResponse(11L, 22L)));
        when(jdbcTemplate.query(contains("primary_raw_article_id"),
                ArgumentMatchers.<RowMapper<Map<String, Object>>>any(), any(Object[].class)))
                .thenReturn(List.of(
                        hydratedRow("IC-002", 22L, "두 번째 카드"),
                        hydratedRow("IC-001", 11L, "첫 번째 카드")));
        // 의미 검색이 limitPerScope(6) 미만(2건)이라 키워드 보충 쿼리가 1회 호출된다 — 빈 결과로 응답.
        when(jdbcTemplate.query(contains("global_search_text"),
                ArgumentMatchers.<RowMapper<Map<String, Object>>>any(), any(Object[].class)))
                .thenReturn(List.of());

        Map<String, Object> result = service.search(request("팔란티어 제휴"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertThat(items).hasSize(2);
        // DB 반환 순서(IC-002 먼저)가 아니라 AI 랭킹 순서(rdb 11 → 22)를 따른다.
        assertThat(items.get(0).get("id")).isEqualTo("IC-001");
        assertThat(items.get(1).get("id")).isEqualTo("IC-002");
        assertThat((double) items.get(0).get("score")).isGreaterThan((double) items.get(1).get("score"));
        assertThat(items.get(0).get("type")).isEqualTo("CARD_NEWS");
    }

    @Test
    @DisplayName("AI 검색 실패 시 기존 ILIKE 키워드 검색으로 폴백한다")
    void semanticFailureFallsBackToKeywordSearch() {
        when(aiClientService.search(any(SearchRequest.class)))
                .thenReturn(Mono.error(new RuntimeException("ai down")));
        when(jdbcTemplate.query(contains("global_search_text"),
                ArgumentMatchers.<RowMapper<Map<String, Object>>>any(), any(Object[].class)))
                .thenReturn(List.of(Map.of(
                        "id", "IC-010", "type", "CARD_NEWS", "title", "키워드 결과",
                        "snippet", "", "badge", "카드뉴스", "target", "issues", "targetId", "IC-010",
                        "date", "2026-06-11", "score", 85.0, "metadata", Map.of())));

        Map<String, Object> result = service.search(request("팔란티어 제휴"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("id")).isEqualTo("IC-010");
        verify(jdbcTemplate, never()).query(contains("primary_raw_article_id"),
                ArgumentMatchers.<RowMapper<Map<String, Object>>>any(), any(Object[].class));
    }

    @Test
    @DisplayName("의미 검색 결과가 모자라면 키워드 결과를 중복 없이 보충한다")
    void semanticResultsMergedWithKeywordWithoutDuplicates() {
        when(aiClientService.search(any(SearchRequest.class))).thenReturn(Mono.just(aiResponse(11L)));
        when(jdbcTemplate.query(contains("primary_raw_article_id"),
                ArgumentMatchers.<RowMapper<Map<String, Object>>>any(), any(Object[].class)))
                .thenReturn(List.of(hydratedRow("IC-001", 11L, "의미 검색 카드")));
        when(jdbcTemplate.query(contains("global_search_text"),
                ArgumentMatchers.<RowMapper<Map<String, Object>>>any(), any(Object[].class)))
                .thenReturn(List.of(
                        keywordItem("IC-001", 110.0),
                        keywordItem("IC-020", 85.0)));

        Map<String, Object> result = service.search(request("팔란티어 제휴"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertThat(items).extracting(item -> item.get("id")).containsExactly("IC-001", "IC-020");
    }

    @Test
    @DisplayName("semantic 비활성 시 AI 호출 없이 키워드 검색만 수행한다")
    void semanticDisabledSkipsAiCall() {
        ReflectionTestUtils.setField(service, "semanticSearchEnabled", false);
        when(jdbcTemplate.query(contains("global_search_text"),
                ArgumentMatchers.<RowMapper<Map<String, Object>>>any(), any(Object[].class)))
                .thenReturn(List.of());

        service.search(request("아무거나"));

        verify(aiClientService, never()).search(any(SearchRequest.class));
    }

    @Test
    @DisplayName("브리핑 검색은 payload/legacy_payload 본문과 검색어 토큰을 함께 조회한다")
    void briefingSearchMatchesPayloadAndQueryTerms() {
        when(jdbcTemplate.query(contains("br.legacy_payload::text"),
                ArgumentMatchers.<RowMapper<Map<String, Object>>>any(), any(Object[].class)))
                .thenReturn(List.of(briefingItem("BR-001", 88.0)));

        Map<String, Object> result = service.search(Map.of(
                "query", "AI 반도체",
                "scopes", List.of("BRIEFING"),
                "limit", 8
        ));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("type")).isEqualTo("BRIEFING");

        ArgumentCaptor<Object[]> paramsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(contains("br.legacy_payload::text"),
                ArgumentMatchers.<RowMapper<Map<String, Object>>>any(), paramsCaptor.capture());
        assertThat(List.of(paramsCaptor.getValue())).contains("%AI 반도체%", "%AI%", "%반도체%");
    }

    @Test
    @DisplayName("복수 scope 검색도 최종 limit을 지키고 hasMore를 계산한다")
    void multiScopeSearchAppliesFinalLimit() {
        ReflectionTestUtils.setField(service, "semanticSearchEnabled", false);
        when(jdbcTemplate.query(contains("br.legacy_payload::text"),
                ArgumentMatchers.<RowMapper<Map<String, Object>>>any(), any(Object[].class)))
                .thenReturn(List.of(
                        searchItem("BRIEFING", "BR-001", 95.0),
                        searchItem("BRIEFING", "BR-002", 65.0)));
        when(jdbcTemplate.query(contains("FROM card_news cn"),
                ArgumentMatchers.<RowMapper<Map<String, Object>>>any(), any(Object[].class)))
                .thenReturn(List.of(
                        searchItem("CARD_NEWS", "IC-001", 90.0),
                        searchItem("CARD_NEWS", "IC-002", 60.0)));
        when(jdbcTemplate.query(contains("FROM peer_companies pc"),
                ArgumentMatchers.<RowMapper<Map<String, Object>>>any(), any(Object[].class)))
                .thenReturn(List.of(
                        searchItem("PEER_PLUS", "samsung_sds", 80.0),
                        searchItem("PEER_PLUS", "lg_cns", 55.0)));

        Map<String, Object> result = service.search(Map.of(
                "query", "AX",
                "scopes", List.of("BRIEFING", "CARD_NEWS", "PEER_PLUS"),
                "limit", 4
        ));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertThat(items).hasSize(4);
        assertThat(result).containsEntry("total", 6);
        assertThat(result).containsEntry("hasMore", true);
    }

    @Test
    @DisplayName("legacy scope 이름(cards, peers, briefings)을 현재 scope로 정규화한다")
    void legacyScopeNamesNormalizeToCurrentScopes() {
        ReflectionTestUtils.setField(service, "semanticSearchEnabled", false);
        when(jdbcTemplate.query(contains("FROM card_news cn"),
                ArgumentMatchers.<RowMapper<Map<String, Object>>>any(), any(Object[].class)))
                .thenReturn(List.of(searchItem("CARD_NEWS", "IC-001", 90.0)));
        when(jdbcTemplate.query(contains("FROM peer_companies pc"),
                ArgumentMatchers.<RowMapper<Map<String, Object>>>any(), any(Object[].class)))
                .thenReturn(List.of(searchItem("PEER_PLUS", "samsung_sds", 80.0)));

        Map<String, Object> result = service.search(Map.of(
                "query", "AX",
                "scopes", List.of("cards", "peers"),
                "limit", 8
        ));

        assertThat(result.get("scopes")).isEqualTo(List.of("CARD_NEWS", "PEER_PLUS"));
    }

    private static Map<String, Object> keywordItem(String id, double score) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("type", "CARD_NEWS");
        item.put("title", "키워드 카드 " + id);
        item.put("snippet", "");
        item.put("badge", "카드뉴스");
        item.put("target", "issues");
        item.put("targetId", id);
        item.put("date", "2026-06-09");
        item.put("score", score);
        item.put("metadata", Map.of());
        return item;
    }

    private static Map<String, Object> briefingItem(String id, double score) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("type", "BRIEFING");
        item.put("title", "브리핑 " + id);
        item.put("snippet", "본문 매칭 브리핑");
        item.put("badge", "브리핑");
        item.put("target", "briefings");
        item.put("targetId", id);
        item.put("date", "2026-06-12");
        item.put("score", score);
        item.put("metadata", Map.of());
        return item;
    }

    private static Map<String, Object> searchItem(String type, String id, double score) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("type", type);
        item.put("title", id);
        item.put("snippet", "");
        item.put("badge", type);
        item.put("target", type.toLowerCase(java.util.Locale.ROOT));
        item.put("targetId", id);
        item.put("date", "2026-06-12");
        item.put("score", score);
        item.put("metadata", Map.of());
        return item;
    }
}
