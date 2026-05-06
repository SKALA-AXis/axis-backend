package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import com.skala.axis.service.ApiContractFixtureService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/briefings")
@RequiredArgsConstructor
public class BriefingController {
    private final ApiContractFixtureService fixture;

    @GetMapping("/today")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getTodayBriefing() {
        return ResponseEntity.ok(ApiResponse.success(fixture.briefing(fixture.defaultBriefingId())));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> listBriefings(@RequestParam Map<String, String> params) {
        return ResponseEntity.ok(ApiResponse.success(fixture.briefingSummaryList()));
    }

    @GetMapping("/cards/search")
    public ResponseEntity<ApiResponse<Map<String, Object>>> searchBriefingCards(@RequestParam Map<String, String> params) {
        return ResponseEntity.ok(ApiResponse.success(fixture.cardList(params)));
    }

    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> generateBriefing(@RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(fixture.briefingGenerationAccepted()));
    }

    @GetMapping("/{briefingId}/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getBriefingGenerationStatus(@PathVariable String briefingId) {
        return ResponseEntity.ok(ApiResponse.success(fixture.briefingStatus(briefingId)));
    }

    @GetMapping("/{briefingId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getBriefingById(@PathVariable String briefingId) {
        return ResponseEntity.ok(ApiResponse.success(fixture.briefing(briefingId)));
    }
}
