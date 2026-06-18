/*
 * 작성일: 2026-05-11
 * 작성자: 박진
 * 변경이력:
 *   2026-05-11 박진 — 프론트 기반 백엔드 대량 수정 시 원문 기사 컨트롤러 추가
 *   2026-05-19 최종민 — DB 정규화에 맞춰 수정
 */
package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import com.skala.axis.service.RawArticleQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/raw-articles")
@RequiredArgsConstructor
public class RawArticleController {
    private final RawArticleQueryService rawArticleQueryService;

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> listRawArticles(@RequestParam Map<String, String> params) {
        return ResponseEntity.ok(ApiResponse.success(rawArticleQueryService.rawArticleList(params)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getRawArticle(@PathVariable int id) {
        return ResponseEntity.ok(ApiResponse.success(rawArticleQueryService.rawArticleDetail(id)));
    }
}
