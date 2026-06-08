package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import com.skala.axis.exception.AiServerException;
import com.skala.axis.service.AssistantConversationService;
import com.skala.axis.service.AiClientService;
import com.skala.axis.service.ApiContractFixtureService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final AssistantConversationService assistantConversationService;

    /**
     * 대화형 도우미 — axis-ai ChatOrchestratorAgent 위임.
     *
     * <p>request body schema:
     * <pre>
     *   { "message": "...", "conversation_id": "..." | null,
     *     "session_id": "..." | null,
     *     "history": [{"role": "...", "content": "..."}],
     *     "current_page": {"route": "...", "visible_item_ids": {...}, "filters": {...}} }
     * </pre>
     *
     * <p>response: axis-ai 의 ChatTurnOutput 그대로 (reply / intent / entities /
     * sources / follow_up_suggestions / final_one_liner / sk_ax_implication /
     * reasoning_steps / confidence / session_id / provenance).
     */
    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sendAssistantChatMessage(
            @RequestBody(required = false) Map<String, Object> request,
            Authentication authentication) {
        Map<String, Object> body = request != null ? request : Map.of();
        String message = body.get("message") instanceof String s ? s : "";

        if (message.isBlank()) {
            log.info("AssistantChat | empty message — fixture stub");
            return ResponseEntity.ok(ApiResponse.success(
                    assistantConversationService.normalizeAssistantResponse(fixture.assistantChat(request))
            ));
        }

        Map<String, Object> prepared = assistantConversationService.prepareChatRequest(body, authentication);
        try {
            Map<String, Object> result = aiClientService.chat(prepared).block();
            if (result == null) {
                log.warn("AssistantChat | axis-ai 응답 null — fixture fallback");
                return ResponseEntity.ok(ApiResponse.success(
                        assistantConversationService.normalizeAssistantResponse(fixture.assistantChat(request))
                ));
            }
            result = assistantConversationService.completeChatTurn(prepared, result);
            log.info("AssistantChat | intent={} session={} confidence={}",
                    result.get("intent"), result.get("session_id"), result.get("confidence"));
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (AiServerException e) {
            log.warn("AssistantChat | axis-ai 호출 실패 — fixture fallback | {}", e.getMessage());
            return ResponseEntity.ok(ApiResponse.success(
                    assistantConversationService.normalizeAssistantResponse(fixture.assistantChat(request))
            ));
        }
    }

    @PostMapping("/conversations")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createConversation(
            @RequestBody(required = false) Map<String, Object> request,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                assistantConversationService.createConversation(request, authentication)
        ));
    }

    @GetMapping("/conversations")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listConversations(
            @RequestParam(name = "device_id", required = false) String deviceId,
            @RequestParam(name = "limit", defaultValue = "20") int limit,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                assistantConversationService.listConversations(authentication, deviceId, limit)
        ));
    }

    @GetMapping("/conversations/{conversationId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> conversationDetail(
            @PathVariable String conversationId,
            @RequestParam(name = "device_id", required = false) String deviceId,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                assistantConversationService.conversationDetail(conversationId, authentication, deviceId)
        ));
    }

    @PostMapping("/conversations/{conversationId}/end")
    public ResponseEntity<ApiResponse<Map<String, Object>>> endConversation(
            @PathVariable String conversationId,
            @RequestBody(required = false) Map<String, Object> request,
            Authentication authentication) {
        String deviceId = request == null ? null : String.valueOf(request.getOrDefault("device_id", ""));
        return ResponseEntity.ok(ApiResponse.success(
                assistantConversationService.endConversation(conversationId, authentication, deviceId)
        ));
    }
}
