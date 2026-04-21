package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/alerts")
public class AlertController {
    @GetMapping("/settings")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSettings() {
        return ResponseEntity.ok(ApiResponse.success(Map.of(
            "briefingTime", "08:30",
            "enableUrgent", true,
            "slackEnabled", false
        )));
    }

    @PutMapping("/settings")
    public ResponseEntity<ApiResponse<Map<String, String>>> updateSettings(@RequestBody Map<String, Object> settings) {
        return ResponseEntity.ok(ApiResponse.success(Map.of("result", "updated")));
    }
}
