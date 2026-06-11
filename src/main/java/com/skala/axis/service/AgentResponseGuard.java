package com.skala.axis.service;

import com.skala.axis.exception.AiServerException;
import org.springframework.http.HttpStatus;

import java.util.Locale;
import java.util.Map;

public final class AgentResponseGuard {
    private AgentResponseGuard() {
    }

    public static void requireSuccess(String agentName, Map<String, Object> result) {
        String prefix = normalizePrefix(agentName);
        if (result == null || result.isEmpty()) {
            throw new AiServerException(
                    prefix + "_AI_EMPTY_RESPONSE",
                    "axis-ai 응답이 비어 있습니다.",
                    HttpStatus.BAD_GATEWAY
            );
        }

        String explicitCode = firstNonBlank(
                stringValue(result.get("error_code")),
                nestedString(result.get("provenance"), "error_code")
        );
        if (isFailurePayload(result)) {
            throw new AiServerException(
                    firstNonBlank(explicitCode, inferredCode(prefix, result)),
                    failureDetail(result),
                    HttpStatus.BAD_GATEWAY
            );
        }
    }

    private static boolean isFailurePayload(Map<String, Object> result) {
        String status = stringValue(result.get("status")).toLowerCase(Locale.ROOT);
        if ("failed".equals(status) || "error".equals(status)) {
            return true;
        }
        if (!stringValue(result.get("error")).isBlank()) {
            return true;
        }
        if (!nestedString(result.get("provenance"), "error").isBlank()) {
            return true;
        }

        String resultKind = firstNonBlank(
                stringValue(result.get("result_kind")),
                nestedString(result.get("provenance"), "result_kind")
        ).toLowerCase(Locale.ROOT);
        if (resultKind.contains("unavailable") || resultKind.contains("empty_axis_ai_response")) {
            return true;
        }

        String warning = stringValue(result.get("warning")).toLowerCase(Locale.ROOT);
        return warning.contains("llm generation failed")
                || warning.contains("llm 호출 실패")
                || warning.contains("generation failed");
    }

    private static String inferredCode(String prefix, Map<String, Object> result) {
        String provenanceError = nestedString(result.get("provenance"), "error");
        String resultKind = firstNonBlank(
                stringValue(result.get("result_kind")),
                nestedString(result.get("provenance"), "result_kind")
        );
        String warning = stringValue(result.get("warning")).toLowerCase(Locale.ROOT);
        if (!provenanceError.isBlank()) {
            return prefix + "_" + normalizeSuffix(provenanceError);
        }
        if (!resultKind.isBlank()) {
            return prefix + "_" + normalizeSuffix(resultKind);
        }
        if (warning.contains("llm generation failed") || warning.contains("llm 호출 실패")) {
            return prefix + "_LLM_GENERATION_FAILED";
        }
        return prefix + "_AI_RESPONSE_FAILED";
    }

    private static String failureDetail(Map<String, Object> result) {
        return firstNonBlank(
                stringValue(result.get("error")),
                nestedString(result.get("provenance"), "error"),
                stringValue(result.get("warning")),
                "axis-ai 응답이 실패 상태입니다."
        );
    }

    private static String normalizePrefix(String value) {
        return normalizeSuffix(value == null || value.isBlank() ? "AGENT" : value);
    }

    private static String normalizeSuffix(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        normalized = normalized.replaceAll("[^A-Z0-9]+", "_");
        normalized = normalized.replaceAll("^_+|_+$", "");
        return normalized.isBlank() ? "AI_RESPONSE_FAILED" : normalized;
    }

    private static String nestedString(Object value, String key) {
        if (value instanceof Map<?, ?> map) {
            Object nested = map.get(key);
            return stringValue(nested);
        }
        return "";
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
