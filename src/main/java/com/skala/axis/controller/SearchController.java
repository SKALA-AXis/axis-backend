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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

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
            return ResponseEntity.ok(ApiResponse.success(emptySearch(request == null ? "" : String.valueOf(request.getOrDefault("query", "")))));
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
            return ResponseEntity.ok(ApiResponse.success(emptySearch(q == null ? "" : q)));
        }
    }

    private static Map<String, Object> emptySearch(String query) {
        return Map.of(
                "query", query,
                "items", java.util.List.of(),
                "counts", Map.of("BRIEFING", 0, "CARD_NEWS", 0, "PEER_PLUS", 0),
                "total", 0,
                "hasMore", false
        );
    }
}
