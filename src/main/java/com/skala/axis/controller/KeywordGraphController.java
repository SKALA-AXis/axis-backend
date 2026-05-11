package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import com.skala.axis.service.ApiContractFixtureService;
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
    private final ApiContractFixtureService fixture;

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> getKeywordGraph(@RequestParam Map<String, String> params) {
        return ResponseEntity.ok(ApiResponse.success(fixture.keywordGraph()));
    }

    @GetMapping("/{nodeId}/cards")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getKeywordGraphNodeCards(
            @PathVariable String nodeId,
            @RequestParam Map<String, String> params
    ) {
        return ResponseEntity.ok(ApiResponse.success(fixture.keywordGraphCards(nodeId, params)));
    }
}
