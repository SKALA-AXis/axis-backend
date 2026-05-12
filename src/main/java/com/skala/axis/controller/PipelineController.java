package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import com.skala.axis.service.ApiContractFixtureService;
import com.skala.axis.service.AiClientService;
import com.skala.axis.service.BriefingService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/pipeline")
@RequiredArgsConstructor
public class PipelineController {
    private final ApiContractFixtureService fixture;
    private final AiClientService aiClientService;
    private final BriefingService briefingService;

    @Value("${axis.scheduler.ingestion-peer-ids}")
    private List<String> ingestionPeerIds;

    @Value("${axis.scheduler.cron-internal-token:}")
    private String cronInternalToken;

    private static final Set<String> SUPPORTED_TRACKS = Set.of("A", "B", "C", "ALL");

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStatus() {
        return ResponseEntity.ok(ApiResponse.success(fixture.pipelineStatus()));
    }

    @PostMapping("/trigger")
    public ResponseEntity<ApiResponse<Map<String, Object>>> trigger(
            @RequestParam(defaultValue = "A") String track,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) Map<String, Object> request
    ) {
        if (!isCronAuthorized(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.success(Map.of("status", "unauthorized")));
        }

        String normalizedTrack = track.trim().toUpperCase();
        if (!SUPPORTED_TRACKS.contains(normalizedTrack)) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.success(Map.of("status", "invalid_track", "track", track)));
        }

        aiClientService.triggerPipeline(normalizedTrack, ingestionPeerIds)
                .subscribe(null, e -> {
                });

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(Map.of(
                "status", "accepted",
                "track", normalizedTrack,
                "peer_ids", ingestionPeerIds
        )));
    }

    @PostMapping("/briefing")
    public ResponseEntity<ApiResponse<Map<String, Object>>> triggerBriefing(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        if (!isCronAuthorized(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.success(Map.of("status", "unauthorized")));
        }
        try {
            briefingService.generateAndSend();
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(ApiResponse.success(Map.of("status", "accepted")));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.success(Map.of("status", "failed", "error", e.getMessage())));
        }
    }

    private boolean isCronAuthorized(String authorization) {
        if (cronInternalToken == null || cronInternalToken.isBlank()) {
            return true;
        }
        return ("Bearer " + cronInternalToken).equals(authorization);
    }
}
