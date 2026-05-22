package com.skala.axis.controller;

import com.skala.axis.config.AuthProperties;
import com.skala.axis.dto.ApiResponse;
import com.skala.axis.service.ApiContractFixtureService;
import com.skala.axis.service.GlobalSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {
    private final ApiContractFixtureService fixture;
    private final GlobalSearchService globalSearchService;
    private final AuthProperties authProperties;

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> search(@RequestBody(required = false) Map<String, Object> request) {
        if (!authProperties.isEnforce()) {
            return ResponseEntity.ok(ApiResponse.success(fixture.globalSearch(Objects.toString(request == null ? "" : request.get("query"), ""))));
        }
        return ResponseEntity.ok(ApiResponse.success(globalSearchService.search(request)));
    }

    @GetMapping("/suggestions")
    public ResponseEntity<ApiResponse<Map<String, Object>>> suggestions(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "6") int limit
    ) {
        if (!authProperties.isEnforce()) {
            return ResponseEntity.ok(ApiResponse.success(fixture.globalSearch(q)));
        }
        return ResponseEntity.ok(ApiResponse.success(globalSearchService.search(Map.of(
                "query", q == null ? "" : q,
                "limit", limit
        ))));
    }
}
