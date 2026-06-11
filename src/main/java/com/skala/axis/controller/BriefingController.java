package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import com.skala.axis.exception.AiServerException;
import com.skala.axis.service.AgentResponseGuard;
import com.skala.axis.service.AiClientService;
import com.skala.axis.service.CardNewsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final AiClientService aiClientService;
    private final CardNewsService cardNewsService;

    @GetMapping("/today")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getTodayBriefing() {
        return ResponseEntity.ok(ApiResponse.success(emptyBriefingsData()));
    }

    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getBriefingSummary(@RequestParam Map<String, String> params) {
        return ResponseEntity.ok(ApiResponse.success(emptyBriefingsData()));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> listBriefings(@RequestParam Map<String, String> params) {
        return ResponseEntity.ok(ApiResponse.success(emptyBriefingsData()));
    }

    @GetMapping("/cards/search")
    public ResponseEntity<ApiResponse<Map<String, Object>>> searchBriefingCards(@RequestParam Map<String, String> params) {
        String peerId = params.get("peer_id");
        String importance = params.get("importance");
        String eventType = params.get("event_type");
        var cards = cardNewsService.getAll(peerId, importance, eventType);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "items", cards,
                "total", cards.size()
        )));
    }

    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> generateBriefing(@RequestBody(required = false) Map<String, Object> request) {
        Map<String, Object> body = request == null ? Map.of() : request;
        try {
            Map<String, Object> result = aiClientService.generateBriefing(body).block();
            AgentResponseGuard.requireSuccess("BRIEFING", result);
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (AiServerException e) {
            log.warn("Briefing axis-ai 호출 실패 | code={} error={}", e.getCode(), e.getMessage());
            return ResponseEntity.status(e.getStatus())
                    .body(ApiResponse.error(e.getCode(), AiServerException.CALL_FAILED_MESSAGE));
        }
    }

    @GetMapping("/{briefingId}/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getBriefingGenerationStatus(@PathVariable String briefingId) {
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "briefing_id", briefingId,
                "status", "not_found",
                "result_kind", "no_saved_briefing"
        )));
    }

    @GetMapping("/{briefingId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getBriefingById(@PathVariable String briefingId) {
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "briefing_id", briefingId,
                "status", "not_found",
                "result_kind", "no_saved_briefing"
        )));
    }

    @PostMapping("/{briefingId}/share")
    public ResponseEntity<ApiResponse<Map<String, Object>>> shareBriefing(
            @PathVariable String briefingId,
            @RequestBody(required = false) Map<String, Object> request
    ) {
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "briefing_id", briefingId,
                "status", "failed",
                "result_kind", "briefing_share_store_unavailable"
        )));
    }

    private static Map<String, Object> emptyBriefingsData() {
        Map<String, Object> snapshot = Map.of(
                "title", "",
                "summary", "",
                "sections", java.util.List.of()
        );
        return Map.of(
                "dailySnapshot", snapshot,
                "weeklySnapshot", snapshot,
                "evidenceSources", java.util.List.of(),
                "history", java.util.List.of()
        );
    }
}
