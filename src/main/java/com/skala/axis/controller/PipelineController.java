package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import com.skala.axis.service.AiClientService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/pipeline")
@RequiredArgsConstructor
public class PipelineController {
    private final AiClientService aiClientService;

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Map<String, String>>> getStatus() {
        return ResponseEntity.ok(ApiResponse.success(Map.of("status", "idle")));
    }

    @PostMapping("/trigger")
    public ResponseEntity<ApiResponse<Map<String, String>>> trigger() {
        aiClientService.triggerPipeline(List.of("samsung_sds", "lg_cns")).subscribe();
        return ResponseEntity.ok(ApiResponse.success(Map.of("result", "triggered")));
    }
}
