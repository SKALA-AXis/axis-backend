package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import com.skala.axis.exception.AiServerException;
import com.skala.axis.service.AiClientService;
import com.skala.axis.service.ApiContractFixtureService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

@Slf4j
@RestController
@RequestMapping("/api/briefings")
@RequiredArgsConstructor
public class BriefingController {
    private final ApiContractFixtureService fixture;
    private final AiClientService aiClientService;

    @GetMapping("/today")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getTodayBriefing() {
        return ResponseEntity.ok(ApiResponse.success(fixture.briefing(fixture.defaultBriefingId())));
    }

    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getBriefingSummary(@RequestParam Map<String, String> params) {
        return ResponseEntity.ok(ApiResponse.success(fixture.briefingWorkspace(params)));
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
        Map<String, Object> body = request == null ? Map.of() : request;
        try {
            Map<String, Object> result = aiClientService.generateBriefing(body).block();
            if (result != null && !result.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.success(result));
            }
        } catch (AiServerException e) {
            log.warn("Briefing axis-ai 호출 실패 — fixture fallback | {}", e.getMessage());
        }
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

    @PostMapping("/{briefingId}/share")
    public ResponseEntity<ApiResponse<Map<String, Object>>> shareBriefing(
            @PathVariable String briefingId,
            @RequestBody(required = false) Map<String, Object> request
    ) {
        Integer expiresInHours = request == null || request.get("expires_in_hours") == null
                ? null
                : Integer.parseInt(String.valueOf(request.get("expires_in_hours")));
        return ResponseEntity.ok(ApiResponse.success(fixture.shareBriefing(briefingId, expiresInHours)));
    }
}
