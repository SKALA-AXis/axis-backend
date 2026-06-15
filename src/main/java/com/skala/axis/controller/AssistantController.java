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
 * <p>{@code POST /api/assistant/chat} 는 axis-ai 의 {@code POST /chat}
 * (ChatOrchestratorAgent)에 위임한다. axis-ai 장애 시 임시 답변을 대신
 * 보여주지 않고 사용자가 재시도할 수 있는 시스템 응답만 반환한다.</p>
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
    private static final String GENERIC_ASSISTANT_ERROR = AiServerException.CALL_FAILED_MESSAGE;
    private static final String ERROR_CHAT_EMPTY_RESPONSE = "ASSISTANT_CHAT_EMPTY_RESPONSE";
    private static final String ERROR_PDF_EMPTY_RESPONSE = "ASSISTANT_PDF_EMPTY_RESPONSE";
    private static final String ERROR_PDF_FILE_REQUIRED = "ASSISTANT_PDF_FILE_REQUIRED";
    private static final String ERROR_PDF_FILE_TOO_LARGE = "ASSISTANT_PDF_FILE_TOO_LARGE";
    private static final String ERROR_PDF_UNSUPPORTED_TYPE = "ASSISTANT_PDF_UNSUPPORTED_TYPE";
    private static final String ERROR_PDF_REQUEST_JSON_INVALID = "ASSISTANT_PDF_REQUEST_JSON_INVALID";

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
                        unavailableChatResponse(
                                prepared,
                                ERROR_CHAT_EMPTY_RESPONSE,
                                "axis-ai returned empty chat response"
                        )
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
                    unavailableChatResponse(prepared, e.getCode(), e.getMessage())
            );
            return ResponseEntity.ok(ApiResponse.success(result));
        }
    }

    @PostMapping(value = "/chat/pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> sendAssistantChatPdfMessage(
            @RequestParam(name = "request_json", required = false) String requestJson,
            @RequestParam(name = "file", required = false) MultipartFile file,
            Authentication authentication) {
        Map<String, Object> body;
        try {
            body = parseRequestJson(requestJson);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.success(
                    invalidPdfChatResponse(new LinkedHashMap<>(), ERROR_PDF_REQUEST_JSON_INVALID, e.getMessage())
            ));
        }

        PdfValidationError validationError = validatePdfFile(file);
        if (validationError != null) {
            return ResponseEntity.ok(ApiResponse.success(
                    invalidPdfChatResponse(body, validationError.code(), validationError.message())
            ));
        }

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
        Map<String, Object> result;
        try {
            result = aiClientService.chatPdf(prepared, file).block();
            if (result == null) {
                result = unavailableChatResponse(
                        prepared,
                        ERROR_PDF_EMPTY_RESPONSE,
                        "axis-ai PDF analysis returned empty response"
                );
            }
        } catch (AiServerException e) {
            log.warn("AssistantChatPdf | axis-ai 호출 실패 — unavailable response | {}", e.getMessage());
            result = unavailableChatResponse(prepared, e.getCode(), e.getMessage());
        }
        result = assistantConversationService.completeChatTurn(prepared, result);
        log.info("AssistantChatPdf | intent={} session={} confidence={}",
                result.get("intent"), result.get("session_id"), result.get("confidence"));
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    private Map<String, Object> invalidPdfChatResponse(
            Map<String, Object> request,
            String errorCode,
            String errorMessage
    ) {
        String conversationId = responseConversationId(request);
        Map<String, Object> response = assistantSystemResponse(
                conversationId,
                GENERIC_ASSISTANT_ERROR + "\n에러코드: " + errorCode,
                "assistant_error",
                "assistant_invalid_request",
                errorCode,
                errorMessage
        );
        response.put("status", "failed");
        response.put("result_kind", "assistant_invalid_request");
        response.put("error_code", errorCode);
        response.put("blocked", true);
        response.put("blocked_reason", errorCode);
        response.put("follow_up_suggestions", List.of("15MB 이하 PDF 파일을 다시 첨부해줘"));
        Object provenance = response.get("provenance");
        if (provenance instanceof Map<?, ?> provenanceMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> mutableProvenance = (Map<String, Object>) provenanceMap;
            mutableProvenance.put("result_kind", "assistant_invalid_request");
        }
        return assistantConversationService.normalizeAssistantResponse(response);
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
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    assistantConversationService.deleteConversation(conversationId, authentication, deviceId)
            ));
        } catch (Exception e) {
            log.warn("AssistantConversationDelete | failed | conversation={} error={}",
                    conversationId, e.getMessage());
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "conversation_id", conversationId,
                    "status", "failed",
                    "deleted", false,
                    "error_code", "ASSISTANT_CONVERSATION_DELETE_FAILED"
            )));
        }
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
        String conversationId = responseConversationId(request);
        Map<String, Object> response = assistantSystemResponse(
                conversationId,
                "질문을 입력해 주세요. 카드뉴스, 오늘 인사이트, 브리핑, 믹서 결과에 대해 답변할 수 있습니다.",
                "empty_message",
                "empty_message",
                null,
                null
        );
        response.put("follow_up_suggestions", List.of("오늘 카드뉴스를 요약해줘", "최근 경쟁사 동향을 알려줘"));
        return response;
    }

    private Map<String, Object> unavailableChatResponse(
            Map<String, Object> request,
            String errorCode,
            String errorMessage
    ) {
        String conversationId = responseConversationId(request);
        Map<String, Object> response = assistantSystemResponse(
                conversationId,
                GENERIC_ASSISTANT_ERROR + "\n에러코드: " + errorCode,
                "assistant_error",
                "assistant_error",
                errorCode,
                errorMessage == null ? "axis-ai unavailable" : errorMessage
        );
        response.put("follow_up_suggestions", List.of());
        response.put("status", "degraded");
        response.put("result_kind", "assistant_unavailable");
        response.put("error_code", errorCode);
        response.put("blocked", true);
        response.put("blocked_reason", errorCode);
        return response;
    }

    private Map<String, Object> assistantSystemResponse(
            String conversationId,
            String reply,
            String intent,
            String retrievalMode,
            String errorCode,
            String errorMessage
    ) {
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("agent", "AssistantController");
        provenance.put("retrieval_mode", retrievalMode);
        provenance.put("is_fixture", false);
        if (errorCode != null && !errorCode.isBlank()) {
            provenance.put("error_code", errorCode);
            provenance.put("result_kind", "assistant_unavailable");
        }
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
        if (errorCode != null && !errorCode.isBlank()) {
            response.put("error_code", errorCode);
        }
        response.put("provenance", provenance);
        return response;
    }

    private String responseConversationId(Map<String, Object> request) {
        Object rawValue = request.get("conversation_id");
        String value = rawValue == null ? "" : String.valueOf(rawValue).trim();
        if (value.isBlank() || "null".equalsIgnoreCase(value)) {
            return UUID.randomUUID().toString();
        }
        return value;
    }

    private PdfValidationError validatePdfFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return new PdfValidationError(ERROR_PDF_FILE_REQUIRED, "PDF 파일이 필요합니다.");
        }
        if (file.getSize() > MAX_PDF_BYTES) {
            return new PdfValidationError(ERROR_PDF_FILE_TOO_LARGE, "PDF 파일은 15MB 이하만 업로드할 수 있습니다.");
        }
        String name = safeFileName(file).toLowerCase(java.util.Locale.ROOT);
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(java.util.Locale.ROOT);
        if (!name.endsWith(".pdf") && !contentType.equals("application/pdf")) {
            return new PdfValidationError(ERROR_PDF_UNSUPPORTED_TYPE, "PDF 파일만 업로드할 수 있습니다.");
        }
        return null;
    }

    private String safeFileName(MultipartFile file) {
        String value = file.getOriginalFilename();
        return value == null || value.isBlank() ? "attachment.pdf" : value;
    }

    private record PdfValidationError(String code, String message) {}
}
