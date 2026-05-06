package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import com.skala.axis.service.ApiContractFixtureService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/mixer")
@RequiredArgsConstructor
public class MixerController {
    private final ApiContractFixtureService fixture;

    @PostMapping
    @SuppressWarnings("unchecked")
    public ResponseEntity<ApiResponse<Map<String, Object>>> runMixer(@RequestBody(required = false) Map<String, Object> request) {
        Object cardIds = request == null ? null : request.get("card_ids");
        return ResponseEntity.ok(ApiResponse.success(fixture.mixerResult(cardIds instanceof List<?> ? (List<String>) cardIds : List.of())));
    }

    @PostMapping("/{mixId}/share")
    public ResponseEntity<ApiResponse<Map<String, Object>>> shareMixerResult(@PathVariable String mixId) {
        return ResponseEntity.ok(ApiResponse.success(fixture.shareMixerResult(mixId)));
    }
}
