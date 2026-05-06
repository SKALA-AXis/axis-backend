package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import com.skala.axis.service.ApiContractFixtureService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/cards")
@RequiredArgsConstructor
public class CardController {
    private final ApiContractFixtureService fixture;

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> listCards(@RequestParam Map<String, String> params) {
        return ResponseEntity.ok(ApiResponse.success(fixture.cardList(params)));
    }

    @GetMapping("/today")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getTodayCards(@RequestParam Map<String, String> params) {
        return ResponseEntity.ok(ApiResponse.success(fixture.todayCards(params)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCardById(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.success(fixture.card(id)));
    }

    @PostMapping("/{id}/verify-link")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyCardLinks(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.success(fixture.verifyLinks(id)));
    }

    @PostMapping("/{id}/share")
    public ResponseEntity<ApiResponse<Map<String, Object>>> shareCard(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> request
    ) {
        Integer expiresInHours = request == null || request.get("expires_in_hours") == null
                ? null
                : ((Number) request.get("expires_in_hours")).intValue();
        return ResponseEntity.ok(ApiResponse.success(fixture.shareCard(id, expiresInHours)));
    }
}
