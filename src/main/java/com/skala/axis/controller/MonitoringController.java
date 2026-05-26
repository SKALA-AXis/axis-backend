package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import com.skala.axis.exception.AiServerException;
import com.skala.axis.service.AiClientService;
import com.skala.axis.service.ApiContractFixtureService;
import com.skala.axis.service.PeerOverviewTableService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Monitoring (Peer+) endpoint.
 *
 * <p>v2 변경: {@code GET /api/monitoring/{peerId}/strategy} 가 fixture stub →
 * axis-ai 의 {@code POST /peer/compare} (PeerComparisonAgent) 위임. axis-ai 미가용 /
 * 에러 시 fixture fallback. window_days / focus_sector 쿼리 파라미터 지원.</p>
 *
 * <p>spec: {@code axis-ai/design/30-analysis/peer-comparison.md}.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/monitoring")
@RequiredArgsConstructor
public class MonitoringController {
    private final ApiContractFixtureService fixture;
    private final AiClientService aiClientService;
    private final PeerOverviewTableService peerOverviewTableService;

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

    @GetMapping("/{peerId:^(?!overview$|comparison$|peer-overview$)[a-zA-Z0-9_]+}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMonitoringPeerDetail(
            @PathVariable String peerId,
            @RequestParam Map<String, String> params
    ) {
        return ResponseEntity.ok(ApiResponse.success(fixture.monitoringPeerDetail(peerId, params)));
    }

    @GetMapping("/{peerId:^(?!overview$|comparison$|peer-overview$)[a-zA-Z0-9_]+}/cards")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMonitoringPeerCardTimeline(@PathVariable String peerId) {
        return ResponseEntity.ok(ApiResponse.success(fixture.monitoringPeerCards(peerId)));
    }

    @GetMapping("/{peerId:^(?!overview$|comparison$|peer-overview$)[a-zA-Z0-9_]+}/financials")
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

    @GetMapping("/overview/peer-table")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMonitoringPeerOverview() {
        return ResponseEntity.ok(ApiResponse.success(peerOverviewTableService.getPeerOverviewTable()));
    }

    /**
     * Peer 전략 분석 — axis-ai PeerComparisonAgent 위임.
     *
     * <p>response payload: axis-ai 의 PeerComparisonOutput 그대로 (peer_id /
     * strategy_label / differentiators / strengths_of_peer / weaknesses_of_peer /
     * collaboration_potential / trend_deltas / forecasts (Day 90+ deferred = []) /
     * sk_ax_implication / final_one_liner / follow_up_questions / reasoning_trail /
     * reasoning_steps / langfuse_trace_id / confidence / sources_used / analysis_period /
     * provenance / warning).
     */
    @GetMapping("/{peerId:^(?!overview$|comparison$|peer-overview$)[a-zA-Z0-9_]+}/strategy")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMonitoringPeerStrategy(
            @PathVariable String peerId,
            @RequestParam(name = "window_days", required = false) Integer windowDays,
            @RequestParam(name = "focus_sector", required = false) String focusSector
    ) {
        try {
            Map<String, Object> result = aiClientService.comparePeer(peerId, windowDays, focusSector).block();
            if (result == null) {
                log.warn("PeerStrategy | axis-ai 응답 null — fixture fallback | peer={}", peerId);
                return ResponseEntity.ok(ApiResponse.success(fixture.peerStrategy(peerId)));
            }
            log.info("PeerStrategy | peer={} strategy={} confidence={}",
                    peerId, result.get("strategy_label"), result.get("confidence"));
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (AiServerException e) {
            log.warn("PeerStrategy | axis-ai 호출 실패 — fixture fallback | peer={} err={}",
                    peerId, e.getMessage());
            return ResponseEntity.ok(ApiResponse.success(fixture.peerStrategy(peerId)));
        }
    }
}
