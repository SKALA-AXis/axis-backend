package com.skala.axis.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skala.axis.dto.ApiResponse;
import com.skala.axis.exception.AiServerException;
import com.skala.axis.service.AssistantConversationService;
import com.skala.axis.service.AiClientService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
    private static final long MAX_PDF_BYTES = 15L * 1024L * 1024L;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final AiClientService aiClientService;
    private final AssistantConversationService assistantConversationService;
    private final ObjectMapper objectMapper;

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
            log.info("AssistantChat | empty message");
            return ResponseEntity.ok(ApiResponse.success(
                    assistantConversationService.normalizeAssistantResponse(emptyChatResponse(body))
            ));
        }

        Map<String, Object> prepared = assistantConversationService.prepareChatRequest(body, authentication);
        try {
            Map<String, Object> result = aiClientService.chat(prepared).block();
            if (result == null) {
                log.warn("AssistantChat | axis-ai 응답 null — unavailable response");
                result = assistantConversationService.completeChatTurn(
                        prepared,
                        unavailableChatResponse(prepared, "axis-ai returned empty chat response")
                );
                return ResponseEntity.ok(ApiResponse.success(
                        result
                ));
            }
            result = assistantConversationService.completeChatTurn(prepared, result);
            log.info("AssistantChat | intent={} session={} confidence={}",
                    result.get("intent"), result.get("session_id"), result.get("confidence"));
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (AiServerException e) {
            log.warn("AssistantChat | axis-ai 호출 실패 — unavailable response | {}", e.getMessage());
            Map<String, Object> result = assistantConversationService.completeChatTurn(
                    prepared,
                    unavailableChatResponse(prepared, e.getMessage())
            );
            return ResponseEntity.ok(ApiResponse.success(result));
        }
    }

    @PostMapping(value = "/chat/pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> sendAssistantChatPdfMessage(
            @RequestParam(name = "request_json", required = false) String requestJson,
            @RequestParam(name = "file") MultipartFile file,
            Authentication authentication) {
        validatePdfFile(file);
        Map<String, Object> body = parseRequestJson(requestJson);
        String message = body.get("message") instanceof String s ? s : "";
        if (message.isBlank()) {
            body.put("message", "첨부 PDF를 분석해줘");
        }
        body.put("attachment", Map.of(
                "file_name", safeFileName(file),
                "content_type", file.getContentType() == null ? "application/pdf" : file.getContentType(),
                "size", file.getSize()
        ));

        Map<String, Object> prepared = assistantConversationService.prepareChatRequest(body, authentication);
        Map<String, Object> result = aiClientService.chatPdf(prepared, file).block();
        if (result == null) {
            throw new AiServerException("axis-ai PDF 분석 응답이 비어 있습니다.");
        }
        result = assistantConversationService.completeChatTurn(prepared, result);
        log.info("AssistantChatPdf | intent={} session={} confidence={}",
                result.get("intent"), result.get("session_id"), result.get("confidence"));
        return ResponseEntity.ok(ApiResponse.success(result));
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

    @DeleteMapping("/conversations/{conversationId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteConversation(
            @PathVariable String conversationId,
            @RequestParam(name = "device_id", required = false) String deviceId,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                assistantConversationService.deleteConversation(conversationId, authentication, deviceId)
        ));
    }

    private Map<String, Object> parseRequestJson(String requestJson) {
        if (requestJson == null || requestJson.isBlank()) {
            return new java.util.LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(requestJson, MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalArgumentException("request_json 형식이 올바르지 않습니다.");
        }
    }

    private Map<String, Object> emptyChatResponse(Map<String, Object> request) {
        String conversationId = String.valueOf(
                request.getOrDefault("conversation_id", UUID.randomUUID().toString())
        );
        Map<String, Object> response = assistantSystemResponse(
                conversationId,
                "질문을 입력해 주세요. 카드뉴스, 오늘 인사이트, 브리핑, 믹서 결과에 대해 답변할 수 있습니다.",
                "empty_message",
                "empty_message",
                null
        );
        response.put("follow_up_suggestions", List.of("오늘 카드뉴스를 요약해줘", "최근 경쟁사 동향을 알려줘"));
        return response;
    }

    private Map<String, Object> unavailableChatResponse(Map<String, Object> request, String errorMessage) {
        String conversationId = String.valueOf(
                request.getOrDefault("conversation_id", UUID.randomUUID().toString())
        );
        Map<String, Object> response = assistantSystemResponse(
                conversationId,
                "AI 서버 응답을 받지 못했습니다. 지금은 임시 목업 답변을 대신 보여주지 않습니다. 잠시 후 다시 시도해 주세요.",
                "assistant_unavailable",
                "axis_ai_unavailable",
                errorMessage == null ? "axis-ai unavailable" : errorMessage
        );
        response.put("follow_up_suggestions", List.of("오늘 카드뉴스를 다시 요약해줘", "최근 경쟁사 동향을 다시 찾아줘"));
        return response;
    }

    private Map<String, Object> assistantSystemResponse(
            String conversationId,
            String reply,
            String intent,
            String retrievalMode,
            String errorMessage
    ) {
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("agent", "AssistantController");
        provenance.put("retrieval_mode", retrievalMode);
        provenance.put("is_fixture", false);
        if (errorMessage != null && !errorMessage.isBlank()) {
            provenance.put("error", errorMessage);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("conversation_id", conversationId);
        response.put("session_id", conversationId);
        response.put("message_id", UUID.randomUUID().toString());
        response.put("reply", reply);
        response.put("intent", intent);
        response.put("scope", "system");
        response.put("answer_blocks", List.of());
        response.put("sources", List.of());
        response.put("related_items", Map.of(
                "cards", List.of(),
                "briefings", List.of(),
                "mixers", List.of(),
                "reports", List.of()
        ));
        response.put("follow_up_suggestions", List.of());
        response.put("confidence", 0.0);
        response.put("blocked", false);
        response.put("blocked_reason", null);
        response.put("handoff", null);
        response.put("provenance", provenance);
        return response;
    }

    private void validatePdfFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("PDF 파일이 필요합니다.");
        }
        if (file.getSize() > MAX_PDF_BYTES) {
            throw new IllegalArgumentException("PDF 파일은 15MB 이하만 업로드할 수 있습니다.");
        }
        String name = safeFileName(file).toLowerCase(java.util.Locale.ROOT);
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(java.util.Locale.ROOT);
        if (!name.endsWith(".pdf") && !contentType.equals("application/pdf")) {
            throw new IllegalArgumentException("PDF 파일만 업로드할 수 있습니다.");
        }
    }

    private String safeFileName(MultipartFile file) {
        String value = file.getOriginalFilename();
        return value == null || value.isBlank() ? "attachment.pdf" : value;
    }
}
