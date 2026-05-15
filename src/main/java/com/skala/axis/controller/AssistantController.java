package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import com.skala.axis.exception.AiServerException;
import com.skala.axis.service.AiClientService;
import com.skala.axis.service.ApiContractFixtureService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Assistant chat endpoint.
 *
 * <p>v2 변경: {@code POST /api/assistant/chat} 가 fixture stub → axis-ai 의
 * {@code POST /chat} (ChatOrchestratorAgent) 위임. intent 분류 + 분석 agent 라우팅
 * + compose. 실패 시 fixture fallback.</p>
 *
 * <p>spec: {@code axis-ai/design/40-user-query/chat-orchestrator.md}.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/assistant")
@RequiredArgsConstructor
public class AssistantController {
    private final ApiContractFixtureService fixture;
    private final AiClientService aiClientService;

    /**
     * 대화형 도우미 — axis-ai ChatOrchestratorAgent 위임.
     *
     * <p>request body schema:
     * <pre>
     *   { "message": "...", "session_id": "..." | null,
     *     "history": [{"role": "...", "content": "..."}, ...] }
     * </pre>
     *
     * <p>response: axis-ai 의 ChatTurnOutput 그대로 (reply / intent / entities /
     * sources / follow_up_suggestions / final_one_liner / sk_ax_implication /
     * reasoning_steps / confidence / session_id / provenance).
     */
    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sendAssistantChatMessage(
            @RequestBody(required = false) Map<String, Object> request) {
        Map<String, Object> body = request != null ? request : Map.of();
        String message = body.get("message") instanceof String s ? s : "";
        String sessionId = body.get("session_id") instanceof String s2 ? s2 : null;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> history = body.get("history") instanceof List<?> h
                ? (List<Map<String, Object>>) h
                : List.of();

        if (message.isBlank()) {
            log.info("AssistantChat | empty message — fixture stub");
            return ResponseEntity.ok(ApiResponse.success(fixture.assistantChat(request)));
        }

        try {
            Map<String, Object> result = aiClientService.chat(message, sessionId, history).block();
            if (result == null) {
                log.warn("AssistantChat | axis-ai 응답 null — fixture fallback");
                return ResponseEntity.ok(ApiResponse.success(fixture.assistantChat(request)));
            }
            log.info("AssistantChat | intent={} session={} confidence={}",
                    result.get("intent"), result.get("session_id"), result.get("confidence"));
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (AiServerException e) {
            log.warn("AssistantChat | axis-ai 호출 실패 — fixture fallback | {}", e.getMessage());
            return ResponseEntity.ok(ApiResponse.success(fixture.assistantChat(request)));
        }
    }
}
