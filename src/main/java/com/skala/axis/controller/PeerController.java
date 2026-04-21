package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import com.skala.axis.dto.IssueCardResponse;
import com.skala.axis.repository.PeerCompanyRepository;
import com.skala.axis.service.IssueCardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/peers")
@RequiredArgsConstructor
public class PeerController {
    private final PeerCompanyRepository peerCompanyRepository;
    private final IssueCardService issueCardService;

    @GetMapping
    public ResponseEntity<ApiResponse<Object>> getPeers() {
        return ResponseEntity.ok(ApiResponse.success(peerCompanyRepository.findAll()));
    }

    @GetMapping("/{peerId}/issues")
    public ResponseEntity<ApiResponse<List<IssueCardResponse>>> getPeerIssues(@PathVariable String peerId) {
        return ResponseEntity.ok(ApiResponse.success(issueCardService.getAll(peerId, null, null)));
    }
}
