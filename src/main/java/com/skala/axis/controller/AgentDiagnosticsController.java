package com.skala.axis.controller;

import com.skala.axis.dto.ApiResponse;
import com.skala.axis.exception.AiServerException;
import com.skala.axis.service.AgentDiagnosticsService;
import com.skala.axis.service.AiClientService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Tag(name = "Agent Diagnostics", description = "로컬/dev 에이전트 실행 및 저장 결과 확인용 API")
@Profile("!prod")
@RestController
@RequestMapping("/api/dev/agents")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "axis.agent-test.enabled", havingValue = "true")
public class AgentDiagnosticsController {
    private final AiClientService aiClientService;
    private final AgentDiagnosticsService diagnosticsService;

    @Value("${ai.server.base-url:http://localhost:8001}")
    private String aiServerBaseUrl;

    @Operation(summary = "axis-ai 상세 헬스 확인", description = "Swagger에서 backend → axis-ai 연결 상태를 확인합니다.")
    @GetMapping("/health")
    public ResponseEntity<ApiResponse<Map<String, Object>>> health() {
        long started = System.nanoTime();
        try {
            Map<String, Object> response = aiClientService.getRaw("/health", Duration.ofSeconds(10)).block();
            return ResponseEntity.ok(ApiResponse.success(mapOf(
                    "ok", true,
                    "axis_ai_base_url", aiServerBaseUrl,
                    "axis_ai_path", "/health",
                    "duration_ms", elapsedMillis(started),
                    "response", response == null ? Map.of() : response
            )));
        } catch (AiServerException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ApiResponse.success(mapOf(
                    "ok", false,
                    "axis_ai_base_url", aiServerBaseUrl,
                    "axis_ai_path", "/health",
                    "duration_ms", elapsedMillis(started),
                    "error", e.getMessage()
            )));
        }
    }

    @Operation(
            summary = "에이전트 직접 실행",
            description = """
                    기존 사용자 API의 임시 응답 없이 axis-ai 내부 endpoint를 직접 호출합니다.
                    agent_type: insight, mixer, global_trends, briefing, link_verify, pipeline, peer, chat, weak_signal.
                    payload를 넣으면 payload가 axis-ai 요청 body로 그대로 전달됩니다.
                    """
    )
    @PostMapping("/run")
    public ResponseEntity<ApiResponse<Map<String, Object>>> runAgent(
            @RequestBody(required = false) AgentRunRequest request) {
        return runAgentByType(null, request);
    }

    @Operation(summary = "에이전트 직접 실행(path type)", description = "agent_type을 path로 지정하는 실행 API입니다.")
    @PostMapping("/{agentType}/run")
    public ResponseEntity<ApiResponse<Map<String, Object>>> runAgentByType(
            @Parameter(description = "insight, mixer, global_trends, briefing, link_verify, pipeline, peer, chat, weak_signal")
            @PathVariable(required = false) String agentType,
            @RequestBody(required = false) AgentRunRequest request) {
        AgentRunRequest body = request == null ? AgentRunRequest.empty() : request;
        AgentDefinition definition = AgentDefinition.from(firstNonBlank(agentType, body.agent_type()));
        Map<String, Object> axisAiRequest = requestBody(definition, body);
        validate(definition, axisAiRequest);

        long started = System.nanoTime();
        try {
            Map<String, Object> response = aiClientService
                    .postRaw(definition.path, axisAiRequest, definition.timeout)
                    .block();
            return ResponseEntity.ok(ApiResponse.success(mapOf(
                    "ok", true,
                    "agent_type", definition.apiName,
                    "axis_ai_base_url", aiServerBaseUrl,
                    "axis_ai_path", definition.path,
                    "duration_ms", elapsedMillis(started),
                    "request", axisAiRequest,
                    "response", response == null ? Map.of() : response
            )));
        } catch (AiServerException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ApiResponse.success(mapOf(
                    "ok", false,
                    "agent_type", definition.apiName,
                    "axis_ai_base_url", aiServerBaseUrl,
                    "axis_ai_path", definition.path,
                    "duration_ms", elapsedMillis(started),
                    "request", axisAiRequest,
                    "error", e.getMessage()
            )));
        }
    }

    @Operation(summary = "에이전트 결과 저장소 타입 목록", description = "조회 가능한 dev 결과 read model 목록을 반환합니다.")
    @GetMapping("/results/types")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resultTypes() {
        return ResponseEntity.ok(ApiResponse.success(diagnosticsService.resultTypes()));
    }

    @Operation(summary = "최근 에이전트 결과 조회", description = "mixer/insight/global_trends/briefing/integrated_issue 결과를 조회합니다.")
    @GetMapping("/results")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listResults(
            @RequestParam(defaultValue = "all") String type,
            @RequestParam(defaultValue = "20") Integer limit,
            @RequestParam(defaultValue = "0") Integer offset) {
        return ResponseEntity.ok(ApiResponse.success(diagnosticsService.listResults(type, limit, offset)));
    }

    @Operation(summary = "에이전트 결과 상세 조회", description = "id 또는 source_analysis_id로 저장된 결과 row 전체를 조회합니다.")
    @GetMapping("/results/{type}/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resultDetail(
            @PathVariable String type,
            @PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.success(diagnosticsService.resultDetail(type, id)));
    }

    private Map<String, Object> requestBody(AgentDefinition definition, AgentRunRequest request) {
        if (request.payload() != null && !request.payload().isEmpty()) {
            return new LinkedHashMap<>(request.payload());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        switch (definition) {
            case INSIGHT -> {
                putIfNotEmpty(body, "card_ids", request.card_ids());
                putIfNotEmpty(body, "context", request.context());
            }
            case MIXER -> {
                putIfNotEmpty(body, "card_ids", request.card_ids());
                putIfNotEmpty(body, "integrated_issue_ids", request.integrated_issue_ids());
                putIfNotEmpty(body, "ratios", request.ratios());
                putIfNotBlank(body, "user_context", request.user_context());
            }
            case GLOBAL_TRENDS -> {
                putIfNotEmpty(body, "company_ids", request.company_ids());
                putIfNotEmpty(body, "peer_company_ids", request.peer_company_ids());
                putIfNotEmpty(body, "focus_themes", request.focus_themes());
                putIfNotEmpty(body, "sk_ax_business_lines", request.sk_ax_business_lines());
                putIfNotNull(body, "window_days", request.window_days());
                putIfNotNull(body, "include_peer_alignment", request.include_peer_alignment());
                putIfNotNull(body, "min_mention_count", request.min_mention_count());
                putIfNotNull(body, "max_trend_count", request.max_trend_count());
            }
            case BRIEFING -> {
                putIfNotBlank(body, "briefing_type", request.briefing_type());
                putIfNotBlank(body, "anchor_date", request.anchor_date());
                putIfNotEmpty(body, "card_ids", request.card_ids());
                putIfNotEmpty(body, "integrated_issue_ids", request.integrated_issue_ids());
                putIfNotEmpty(body, "peer_ids", request.peer_ids());
                putIfNotEmpty(body, "sectors", request.sectors());
                putIfNotNull(body, "requested_by_user_id", request.requested_by_user_id());
                putIfNotEmpty(body, "ratios", request.ratios());
                putIfNotBlank(body, "user_context", request.user_context());
                putIfNotNull(body, "limit", request.limit());
                putIfNotNull(body, "save", request.save());
                putIfNotNull(body, "refine_display_copy", request.refine_display_copy());
            }
            case LINK_VERIFY -> putIfNotBlank(body, "card_id", request.card_id());
            case PIPELINE -> {
                body.put("track", firstNonBlank(request.track(), "A"));
                body.put("company", request.company() == null || request.company().isEmpty()
                        ? List.of("samsung_sds")
                        : request.company());
                body.put("trigger_type", firstNonBlank(request.trigger_type(), "manual_swagger"));
                putIfNotBlank(body, "window_start", request.window_start());
                putIfNotBlank(body, "window_end", request.window_end());
            }
            case PEER -> {
                putIfNotBlank(body, "peer_id", request.peer_id());
                putIfNotNull(body, "window_days", request.window_days());
                putIfNotBlank(body, "focus_sector", request.focus_sector());
            }
            case CHAT -> {
                putIfNotBlank(body, "message", request.message());
                putIfNotBlank(body, "session_id", request.session_id());
                putIfNotEmpty(body, "history", request.history());
            }
            case WEAK_SIGNAL -> {
            }
        }
        return body;
    }

    private void validate(AgentDefinition definition, Map<String, Object> request) {
        switch (definition) {
            case INSIGHT -> requireList(request, "card_ids", "insight 실행에는 card_ids가 필요합니다.");
            case MIXER -> {
                if (!hasList(request, "card_ids") && !hasList(request, "integrated_issue_ids")) {
                    throw new IllegalArgumentException("mixer 실행에는 card_ids 또는 integrated_issue_ids가 필요합니다.");
                }
            }
            case LINK_VERIFY -> requireString(request, "card_id", "link_verify 실행에는 card_id가 필요합니다.");
            case PEER -> requireString(request, "peer_id", "peer 실행에는 peer_id가 필요합니다.");
            case CHAT -> requireString(request, "message", "chat 실행에는 message가 필요합니다.");
            default -> {
            }
        }
    }

    private static boolean hasList(Map<String, Object> body, String key) {
        return body.get(key) instanceof List<?> list && !list.isEmpty();
    }

    private static void requireList(Map<String, Object> body, String key, String message) {
        if (!hasList(body, key)) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void requireString(Map<String, Object> body, String key, String message) {
        if (!(body.get(key) instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void putIfNotBlank(Map<String, Object> body, String key, String value) {
        if (value != null && !value.isBlank()) {
            body.put(key, value);
        }
    }

    private static void putIfNotNull(Map<String, Object> body, String key, Object value) {
        if (value != null) {
            body.put(key, value);
        }
    }

    private static void putIfNotEmpty(Map<String, Object> body, String key, Object value) {
        if (value instanceof List<?> list && !list.isEmpty()) {
            body.put(key, value);
        } else if (value instanceof Map<?, ?> map && !map.isEmpty()) {
            body.put(key, value);
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static long elapsedMillis(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }

    private static Map<String, Object> mapOf(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            map.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return map;
    }

    private enum AgentDefinition {
        INSIGHT("insight", "/insight/generate", Duration.ofSeconds(90)),
        MIXER("mixer", "/mixer/analyze", Duration.ofSeconds(90)),
        GLOBAL_TRENDS("global_trends", "/global/trends/run", Duration.ofSeconds(140)),
        BRIEFING("briefing", "/briefing/generate", Duration.ofSeconds(140)),
        LINK_VERIFY("link_verify", "/link/verify", Duration.ofSeconds(30)),
        PIPELINE("pipeline", "/pipeline/run", Duration.ofSeconds(15)),
        PEER("peer", "/peer/compare", Duration.ofSeconds(90)),
        CHAT("chat", "/chat", Duration.ofSeconds(90)),
        WEAK_SIGNAL("weak_signal", "/weak-signal/run", Duration.ofSeconds(15));

        private final String apiName;
        private final String path;
        private final Duration timeout;

        AgentDefinition(String apiName, String path, Duration timeout) {
            this.apiName = apiName;
            this.path = path;
            this.timeout = timeout;
        }

        private static AgentDefinition from(String rawType) {
            if (rawType == null || rawType.isBlank()) {
                throw new IllegalArgumentException("agent_type은 필수입니다.");
            }
            String type = rawType.trim().toLowerCase(Locale.ROOT).replace("-", "_");
            for (AgentDefinition definition : values()) {
                if (definition.apiName.equals(type)) {
                    return definition;
                }
            }
            throw new IllegalArgumentException("지원하지 않는 agent_type입니다: " + rawType);
        }
    }

    @Schema(description = "Swagger 에이전트 실행 요청. payload가 있으면 payload를 axis-ai 요청 body로 그대로 사용합니다.")
    public record AgentRunRequest(
            @Schema(example = "insight") String agent_type,
            @Schema(description = "axis-ai로 그대로 전달할 원본 request body") Map<String, Object> payload,
            @Schema(example = "[\"CN-20260502-001\", \"CN-20260502-002\"]") List<String> card_ids,
            List<String> integrated_issue_ids,
            Map<String, Object> context,
            Map<String, Object> ratios,
            String user_context,
            String peer_id,
            Integer window_days,
            String focus_sector,
            List<String> company_ids,
            List<String> peer_company_ids,
            List<String> focus_themes,
            List<String> sk_ax_business_lines,
            Boolean include_peer_alignment,
            Integer min_mention_count,
            Integer max_trend_count,
            String message,
            String session_id,
            List<Map<String, Object>> history,
            String card_id,
            String briefing_type,
            String anchor_date,
            List<String> peer_ids,
            List<String> sectors,
            Integer requested_by_user_id,
            Integer limit,
            Boolean save,
            Boolean refine_display_copy,
            String track,
            List<String> company,
            String trigger_type,
            String window_start,
            String window_end
    ) {
        private static AgentRunRequest empty() {
            return new AgentRunRequest(
                    null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null
            );
        }
    }
}
