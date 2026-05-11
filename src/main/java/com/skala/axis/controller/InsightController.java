package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import com.skala.axis.service.ApiContractFixtureService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/insights")
@RequiredArgsConstructor
public class InsightController {
    private final ApiContractFixtureService fixture;

    @GetMapping("/latest")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getLatestInsight(@RequestParam Map<String, String> params) {
        return ResponseEntity.ok(ApiResponse.success(fixture.latestInsight()));
    }

    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> generateInsight(@RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(fixture.insightGenerationAccepted()));
    }
}
