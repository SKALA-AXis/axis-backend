package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import com.skala.axis.dto.SearchRequest;
import com.skala.axis.dto.SearchResponse;
import com.skala.axis.service.AiClientService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {
    private final AiClientService aiClientService;

    @PostMapping
    public ResponseEntity<ApiResponse<SearchResponse>> search(@RequestBody SearchRequest request) {
        SearchResponse result = aiClientService.search(request).block();
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/suggestions")
    public ResponseEntity<ApiResponse<Object>> suggestions(@RequestParam String q) {
        return ResponseEntity.ok(ApiResponse.success(java.util.List.of()));
    }
}
