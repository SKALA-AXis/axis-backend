package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import com.skala.axis.service.ApiContractFixtureService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {
    private final ApiContractFixtureService fixture;

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> search(@RequestBody(required = false) Map<String, Object> request) {
        Object rawQuery = request == null ? null : request.get("query");
        String query = rawQuery == null ? null : String.valueOf(rawQuery);
        return ResponseEntity.ok(ApiResponse.success(fixture.globalSearch(query)));
    }

    @GetMapping("/suggestions")
    public ResponseEntity<ApiResponse<Map<String, Object>>> suggestions(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "6") int limit
    ) {
        return ResponseEntity.ok(ApiResponse.success(fixture.globalSearch(q)));
    }
}
