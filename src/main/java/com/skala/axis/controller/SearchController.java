/*
 * 작성일: 2026-04-21
 * 작성자: 최종민
 * 변경이력:
 *   2026-04-21 최종민 — axis-backend 베이스라인 생성
 *   2026-05-11 박진 — 프론트 기반 백엔드 대량 수정, 카드뉴스·알림 설정, 챗봇 로직 수정
 *   2026-06-14 안가은 — 대시보드·전역 검색 API 보강
 */
package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import com.skala.axis.service.GlobalSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {
    private final GlobalSearchService globalSearchService;

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> search(@RequestBody(required = false) Map<String, Object> request) {
        try {
            return ResponseEntity.ok(ApiResponse.success(globalSearchService.search(request)));
        } catch (RuntimeException e) {
            log.error("Global search failed | query={}", queryOf(request), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("SEARCH_FAILED", "검색 결과를 불러오지 못했습니다."));
        }
    }

    @GetMapping("/suggestions")
    public ResponseEntity<ApiResponse<Map<String, Object>>> suggestions(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "6") int limit
    ) {
        try {
            return ResponseEntity.ok(ApiResponse.success(globalSearchService.search(Map.of(
                    "query", q == null ? "" : q,
                    "limit", limit
            ))));
        } catch (RuntimeException e) {
            log.error("Global search suggestions failed | query={}", q == null ? "" : q, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("SEARCH_FAILED", "검색 결과를 불러오지 못했습니다."));
        }
    }

    private static String queryOf(Map<String, Object> request) {
        return request == null ? "" : String.valueOf(request.getOrDefault("query", ""));
    }
}
