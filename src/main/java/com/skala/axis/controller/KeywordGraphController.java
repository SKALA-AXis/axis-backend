/*
 * 작성일: 2026-05-11
 * 작성자: 박진
 * 변경이력:
 *   2026-05-11 박진 — 프론트 연동 위한 백엔드 대량 수정
 *   2026-06-01 안가은 — 원문 기사 기반 키워드 그래프 API 추가
 */
package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import com.skala.axis.service.KeywordGraphService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/keyword-graph")
@RequiredArgsConstructor
public class KeywordGraphController {
    private final KeywordGraphService keywordGraphService;

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> getKeywordGraph(@RequestParam Map<String, String> params) {
        return ResponseEntity.ok(ApiResponse.success(keywordGraphService.keywordGraph()));
    }

    @GetMapping("/{nodeId}/cards")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getKeywordGraphNodeCards(
            @PathVariable String nodeId,
            @RequestParam Map<String, String> params
    ) {
        return ResponseEntity.ok(ApiResponse.success(keywordGraphService.keywordGraphCards(nodeId, params)));
    }
}
