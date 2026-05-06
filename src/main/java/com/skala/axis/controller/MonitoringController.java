package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import com.skala.axis.service.ApiContractFixtureService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/monitoring")
@RequiredArgsConstructor
public class MonitoringController {
    private final ApiContractFixtureService fixture;

    @GetMapping("/overview")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMonitoringOverview(@RequestParam Map<String, String> params) {
        return ResponseEntity.ok(ApiResponse.success(fixture.monitoringOverview(params)));
    }

    @GetMapping("/cards/search")
    public ResponseEntity<ApiResponse<Map<String, Object>>> searchMonitoringCards(@RequestParam Map<String, String> params) {
        return ResponseEntity.ok(ApiResponse.success(fixture.cardList(params)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> listMonitoringPeers() {
        return ResponseEntity.ok(ApiResponse.success(fixture.monitoringPeers()));
    }

    @GetMapping("/{peerId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMonitoringPeerDetail(
            @PathVariable String peerId,
            @RequestParam Map<String, String> params
    ) {
        return ResponseEntity.ok(ApiResponse.success(fixture.monitoringPeerDetail(peerId, params)));
    }

    @GetMapping("/{peerId}/cards")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMonitoringPeerCardTimeline(@PathVariable String peerId) {
        return ResponseEntity.ok(ApiResponse.success(fixture.monitoringPeerCards(peerId)));
    }

    @GetMapping("/{peerId}/financials")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMonitoringPeerFinancials(
            @PathVariable String peerId,
            @RequestParam(defaultValue = "8") int quarters
    ) {
        return ResponseEntity.ok(ApiResponse.success(fixture.financials(peerId, quarters)));
    }

    @GetMapping("/comparison")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMonitoringComparison(
            @RequestParam(defaultValue = "revenue") String metric,
            @RequestParam(defaultValue = "8") int quarters
    ) {
        return ResponseEntity.ok(ApiResponse.success(fixture.monitoringComparison(metric, quarters)));
    }

    @GetMapping("/{peerId}/strategy")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMonitoringPeerStrategy(@PathVariable String peerId) {
        return ResponseEntity.ok(ApiResponse.success(fixture.peerStrategy(peerId)));
    }
}
