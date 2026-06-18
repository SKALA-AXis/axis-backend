/*
 * 작성일: 2026-05-06
 * 작성자: 박진
 * 변경이력:
 *   2026-05-06 박진 — Backend 초안 작성 후 챗봇 로직 수정 및 AI 연동 강화
 *   2026-05-15 최종민 — /api/monitoring/{peerId}/strategy fixture를 axis-ai /peer/compare 로 연동
 *   2026-05-26 안가은 — Peer+ 페이지 및 재무표 연동
 */
package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import com.skala.axis.exception.AiServerException;
import com.skala.axis.service.AgentResponseGuard;
import com.skala.axis.service.AiClientService;
import com.skala.axis.service.CardNewsService;
import com.skala.axis.service.PeerOverviewTableService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Monitoring (Peer+) endpoint.
 *
 * <p>{@code GET /api/monitoring/{peerId}/strategy} 는 axis-ai 의
 * {@code POST /peer/compare} (PeerComparisonAgent)에 위임한다. axis-ai 미가용 /
 * 에러 시 실패 상태를 반환한다. window_days / focus_sector 쿼리 파라미터 지원.</p>
 *
 * <p>spec: {@code axis-ai/design/30-analysis/peer-comparison.md}.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/monitoring")
@RequiredArgsConstructor
public class MonitoringController {
    private final AiClientService aiClientService;
    private final PeerOverviewTableService peerOverviewTableService;
    private final CardNewsService cardNewsService;

    @GetMapping("/overview")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMonitoringOverview(@RequestParam Map<String, String> params) {
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "period", params,
                "metrics", List.of(),
                "peerScores", List.of(),
                "items", List.of()
        )));
    }

    @GetMapping("/cards/search")
    public ResponseEntity<ApiResponse<Map<String, Object>>> searchMonitoringCards(@RequestParam Map<String, String> params) {
        var cards = cardNewsService.getAll(params.get("peer_id"), params.get("importance"), params.get("event_type"));
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "items", cards,
                "total", cards.size()
        )));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> listMonitoringPeers() {
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "items", List.of(),
                "total", 0
        )));
    }

    @GetMapping("/{peerId:^(?!overview$|comparison$|peer-overview$)[a-zA-Z0-9_]+}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMonitoringPeerDetail(
            @PathVariable String peerId,
            @RequestParam Map<String, String> params
    ) {
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "peer_id", peerId,
                "status", "not_found",
                "result_kind", "no_saved_monitoring_peer"
        )));
    }

    @GetMapping("/{peerId:^(?!overview$|comparison$|peer-overview$)[a-zA-Z0-9_]+}/cards")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMonitoringPeerCardTimeline(@PathVariable String peerId) {
        var cards = cardNewsService.getAll(peerId, null, null);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "items", cards,
                "total", cards.size()
        )));
    }

    @GetMapping("/{peerId:^(?!overview$|comparison$|peer-overview$)[a-zA-Z0-9_]+}/financials")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMonitoringPeerFinancials(
            @PathVariable String peerId,
            @RequestParam(defaultValue = "8") int quarters
    ) {
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "peer_id", peerId,
                "quarters", quarters,
                "status", "not_found",
                "result_kind", "no_saved_financials"
        )));
    }

    @GetMapping("/comparison")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMonitoringComparison(
            @RequestParam(defaultValue = "revenue") String metric,
            @RequestParam(defaultValue = "8") int quarters
    ) {
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "metric", metric,
                "quarters", quarters,
                "series", List.of()
        )));
    }

    @GetMapping("/overview/peer-table")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMonitoringPeerOverview() {
        return ResponseEntity.ok(ApiResponse.success(peerOverviewTableService.getPeerOverviewTable()));
    }

    @GetMapping("/overview/positioning")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMonitoringPeerPositioning() {
        return ResponseEntity.ok(ApiResponse.success(peerOverviewTableService.getPeerPositioningChart()));
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
            AgentResponseGuard.requireSuccess("PEER", result);
            log.info("PeerStrategy | peer={} strategy={} confidence={}",
                    peerId, result.get("strategy_label"), result.get("confidence"));
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (AiServerException e) {
            log.warn("PeerStrategy | axis-ai 호출 실패 | peer={} code={} err={}",
                    peerId, e.getCode(), e.getMessage());
            return ResponseEntity.status(e.getStatus())
                    .body(ApiResponse.error(e.getCode(), AiServerException.CALL_FAILED_MESSAGE));
        }
    }
}
