package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import com.skala.axis.exception.AiServerException;
import com.skala.axis.service.AgentResponseGuard;
import com.skala.axis.service.AiClientService;
import com.skala.axis.service.BriefingReportService;
import com.skala.axis.service.CardNewsService;
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

import java.time.LocalDate;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/briefings")
@RequiredArgsConstructor
public class BriefingController {
    private final AiClientService aiClientService;
    private final BriefingReportService briefingReportService;
    private final CardNewsService cardNewsService;

    @GetMapping("/today")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getTodayBriefing() {
        return briefingReportService.findLatestPayload("daily", LocalDate.now())
                .map(payload -> ResponseEntity.ok(ApiResponse.success(payload)))
                .orElseGet(() -> briefingUnavailable("BRIEFING_REPORT_UNAVAILABLE"));
    }

    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getBriefingSummary(@RequestParam Map<String, String> params) {
        LocalDate anchorDate = parseAnchorDate(params.get("anchor_date"));
        return briefingReportService.findOverview(anchorDate)
                .map(payload -> ResponseEntity.ok(ApiResponse.success(payload)))
                .orElseGet(() -> briefingUnavailable("BRIEFING_REPORT_UNAVAILABLE"));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> listBriefings(@RequestParam Map<String, String> params) {
        LocalDate anchorDate = parseAnchorDate(params.get("anchor_date"));
        return briefingReportService.findOverview(anchorDate)
                .map(payload -> ResponseEntity.ok(ApiResponse.success(payload)))
                .orElseGet(() -> briefingUnavailable("BRIEFING_REPORT_UNAVAILABLE"));
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
        return briefingReportService.findStatus(briefingId)
                .map(payload -> ResponseEntity.ok(ApiResponse.success(payload)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.<Map<String, Object>>error("BRIEFING_REPORT_NOT_FOUND", "저장된 브리핑 결과가 없습니다.")));
    }

    @GetMapping("/{briefingId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getBriefingById(@PathVariable String briefingId) {
        return briefingReportService.findById(briefingId)
                .map(payload -> ResponseEntity.ok(ApiResponse.success(payload)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.<Map<String, Object>>error("BRIEFING_REPORT_NOT_FOUND", "저장된 브리핑 결과가 없습니다.")));
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

    private static ResponseEntity<ApiResponse<Map<String, Object>>> briefingUnavailable(String code) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.<Map<String, Object>>error(code, "저장된 브리핑 결과가 없습니다."));
    }

    private static LocalDate parseAnchorDate(String raw) {
        try {
            return raw == null || raw.isBlank() ? LocalDate.now() : LocalDate.parse(raw);
        } catch (Exception ignored) {
            return LocalDate.now();
        }
    }
}
