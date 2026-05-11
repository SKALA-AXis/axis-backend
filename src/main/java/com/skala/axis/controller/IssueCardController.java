package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import com.skala.axis.service.ApiContractFixtureService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/issues")
@RequiredArgsConstructor
public class IssueCardController {
    private final ApiContractFixtureService fixture;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getIssues(
            @RequestParam(required = false) String peerId,
            @RequestParam(required = false) String importance,
            @RequestParam(required = false) String eventType) {
        return ResponseEntity.ok(ApiResponse.success(fixture.frontendIssues()));
    }

    @GetMapping("/today")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getTodayIssues(
            @RequestParam(required = false) String peerId,
            @RequestParam(required = false) String importance) {
        return ResponseEntity.ok(ApiResponse.success(fixture.frontendIssues()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getById(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.success(fixture.issueSummary(id)));
    }
}
