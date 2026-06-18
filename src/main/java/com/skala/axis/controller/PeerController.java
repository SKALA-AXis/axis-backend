/*
 * 작성일: 2026-04-21
 * 작성자: 최종민
 * 변경이력:
 *   2026-04-21 최종민 — axis-backend 베이스라인 구성
 *   2026-05-11 박진 — 프론트 연동 위한 백엔드 대량 수정 및 챗봇 로직 수정
 */
package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import com.skala.axis.service.CardNewsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/peers")
@RequiredArgsConstructor
public class PeerController {
    private final CardNewsService cardNewsService;

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPeers(@RequestParam Map<String, String> params) {
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "peers", List.of(),
                "periodLabels", Map.of(
                        "yearly", "연간",
                        "quarterly", "분기",
                        "monthly", "월간"
                ),
                "periodDescriptions", Map.of(
                        "yearly", "연간 분석 데이터가 연결되면 표시됩니다.",
                        "quarterly", "분기 분석 데이터가 연결되면 표시됩니다.",
                        "monthly", "월간 분석 데이터가 연결되면 표시됩니다."
                ),
                "analyses", Map.of()
        )));
    }

    @GetMapping("/{peerId}/profile")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPeerProfile(
            @PathVariable String peerId,
            @RequestParam(defaultValue = "true") boolean include_cards
    ) {
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "peer_id", peerId,
                "status", "not_found",
                "result_kind", "no_saved_peer_profile"
        )));
    }

    @GetMapping("/{peerId}/issues")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getPeerIssues(@PathVariable String peerId) {
        return ResponseEntity.ok(ApiResponse.success(
                cardNewsService.getAll(peerId, null, null).stream()
                        .map(card -> Map.<String, Object>of(
                                "id", card.getId(),
                                "title", card.getTitle() == null ? "" : card.getTitle(),
                                "summaryLines", card.getSummaryLines() == null ? List.of() : card.getSummaryLines(),
                                "createdAt", card.getCreatedAt() == null ? "" : card.getCreatedAt().toString(),
                                "sourceUrl", card.getSourceUrl() == null ? "" : card.getSourceUrl()
                        ))
                        .toList()
        ));
    }
}
