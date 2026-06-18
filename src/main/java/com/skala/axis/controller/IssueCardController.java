package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import com.skala.axis.dto.CardNewsResponse;
import com.skala.axis.formatter.IssueImportanceClassifier;
import com.skala.axis.service.CardNewsService;
import com.skala.axis.service.PeerCompanyProvider;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/issues")
@RequiredArgsConstructor
public class IssueCardController {
    private final CardNewsService cardNewsService;
    private final PeerCompanyProvider peerCompanyProvider;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getIssues(
            @RequestParam(required = false) String peerId,
            @RequestParam(required = false) String importance,
            @RequestParam(required = false) String eventType) {
        return ResponseEntity.ok(ApiResponse.success(
                cardNewsService.getAll(peerId, importance, eventType).stream()
                        .map(this::toIssuePayload)
                        .toList()
        ));
    }

    @GetMapping("/today")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getTodayIssues(
            @RequestParam(required = false) String peerId,
            @RequestParam(required = false) String importance) {
        return ResponseEntity.ok(ApiResponse.success(
                cardNewsService.getTodayCards(peerId, importance).stream()
                        .map(this::toIssuePayload)
                        .toList()
        ));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getById(@PathVariable String id) {
        try {
            return ResponseEntity.ok(ApiResponse.success(toIssuePayload(cardNewsService.getById(id))));
        } catch (EntityNotFoundException e) {
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "id", id,
                    "status", "not_found",
                    "result_kind", "no_saved_issue"
            )));
        }
    }

    private Map<String, Object> toIssuePayload(CardNewsResponse card) {
        return Map.of(
                "id", card.getId(),
                "peerId", card.getPeerId() == null ? "" : card.getPeerId(),
                "peerName", peerCompanyProvider.displayName(card.getPeerId()),
                "title", card.getTitle() == null ? "" : card.getTitle(),
                "summaryLines", card.getSummaryLines() == null ? List.of() : card.getSummaryLines(),
                "importance", issueImportance(card),
                "createdAt", card.getCreatedAt() == null ? "" : card.getCreatedAt().toString(),
                "sourceUrl", card.getSourceUrl() == null ? "" : card.getSourceUrl()
        );
    }

    private static String issueImportance(CardNewsResponse card) {
        return IssueImportanceClassifier.classify(card.getImportance(), card.getImportanceScore());
    }

}
