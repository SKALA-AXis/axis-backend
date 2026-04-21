package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import com.skala.axis.dto.IssueCardResponse;
import com.skala.axis.service.IssueCardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/issues")
@RequiredArgsConstructor
public class IssueCardController {
    private final IssueCardService issueCardService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<IssueCardResponse>>> getIssues(
            @RequestParam(required = false) String peerId,
            @RequestParam(required = false) String importance,
            @RequestParam(required = false) String eventType) {
        return ResponseEntity.ok(ApiResponse.success(issueCardService.getAll(peerId, importance, eventType)));
    }

    @GetMapping("/today")
    public ResponseEntity<ApiResponse<List<IssueCardResponse>>> getTodayIssues(
            @RequestParam(required = false) String peerId,
            @RequestParam(required = false) String importance) {
        return ResponseEntity.ok(ApiResponse.success(issueCardService.getTodayIssues(peerId, importance)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<IssueCardResponse>> getById(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.success(issueCardService.getById(id)));
    }
}
