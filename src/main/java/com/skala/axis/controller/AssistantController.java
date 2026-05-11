package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import com.skala.axis.service.ApiContractFixtureService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/assistant")
@RequiredArgsConstructor
public class AssistantController {
    private final ApiContractFixtureService fixture;

    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sendAssistantChatMessage(@RequestBody(required = false) Map<String, Object> request) {
        return ResponseEntity.ok(ApiResponse.success(fixture.assistantChat(request)));
    }
}
