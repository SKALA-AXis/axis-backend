package com.skala.axis.service;

import com.skala.axis.exception.AiServerException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentResponseGuardTest {
    @Test
    void requireSuccessRejectsFallbackResultKind() {
        assertThrows(AiServerException.class, () -> AgentResponseGuard.requireSuccess(
                "TODAY_INSIGHT",
                Map.of("provenance", Map.of(
                        "mode", "deterministic_fallback",
                        "result_kind", "generated_fallback"
                ))
        ));
    }

    @Test
    void requireSuccessAllowsNormalPayload() {
        assertDoesNotThrow(() -> AgentResponseGuard.requireSuccess(
                "MIXER",
                Map.of("status", "completed", "confidence", 0.82)
        ));
    }
}
