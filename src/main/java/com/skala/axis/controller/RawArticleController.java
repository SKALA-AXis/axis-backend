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
