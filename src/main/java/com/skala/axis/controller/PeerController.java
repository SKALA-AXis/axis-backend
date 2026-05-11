package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import com.skala.axis.service.ApiContractFixtureService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/peers")
@RequiredArgsConstructor
public class PeerController {
    private final ApiContractFixtureService fixture;

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPeers(@RequestParam Map<String, String> params) {
        return ResponseEntity.ok(ApiResponse.success(fixture.frontendPeers()));
    }

    @GetMapping("/{peerId}/profile")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPeerProfile(
            @PathVariable String peerId,
            @RequestParam(defaultValue = "true") boolean include_cards
    ) {
        return ResponseEntity.ok(ApiResponse.success(fixture.peerProfile(peerId, include_cards)));
    }

    @GetMapping("/{peerId}/issues")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getPeerIssues(@PathVariable String peerId) {
        return ResponseEntity.ok(ApiResponse.success(fixture.frontendIssues()));
    }
}
