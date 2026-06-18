/*
 * 작성일: 2026-06-11
 * 작성자: 박진
 * 변경이력:
 *   2026-06-11 박진 — 챗봇 AI 연동 강화 시 추가, 이후 목업 삭제 및 챗봇 고도화 반영
 */
package com.skala.axis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skala.axis.config.AuthPrincipal;
import com.skala.axis.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssistantConversationServiceTest {
    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void prepareChatRequestReplacesConversationIdWhenOwnerDoesNotMatch() {
        UUID requestedConversationId = UUID.randomUUID();
        UUID currentUserId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        AssistantConversationService service = new AssistantConversationService(
                jdbcTemplate,
                new ObjectMapper()
        );

        when(jdbcTemplate.queryForList(
                argThat(sql -> sql != null && sql.contains("FROM assistant_conversations")),
                eq(requestedConversationId)
        )).thenReturn(List.of(Map.of(
                "user_id", otherUserId.toString(),
                "device_id_hash", "",
                "status", "active"
        )));

        Map<String, Object> result = service.prepareChatRequest(
                Map.of(
                        "conversation_id", requestedConversationId.toString(),
                        "message", "오늘 인사이트 요약해줘"
                ),
                new UsernamePasswordAuthenticationToken(
                        new AuthPrincipal(currentUserId, "axis.user@sk.com", UserRole.USER),
                        null,
                        List.of()
                )
        );

        assertThat(result.get("conversation_id"))
                .isNotEqualTo(requestedConversationId.toString())
                .isEqualTo(result.get("session_id"));
    }

    @Test
    void deleteConversationSoftDeletesAndIgnoresPurgeFailure() {
        UUID conversationId = UUID.randomUUID();
        AssistantConversationService service = new AssistantConversationService(
                jdbcTemplate,
                new ObjectMapper()
        );

        when(jdbcTemplate.update(
                argThat(sql -> sql != null && sql.contains("UPDATE assistant_conversations")),
                eq(conversationId),
                any()
        )).thenReturn(1);
        when(jdbcTemplate.update(
                argThat(sql -> sql != null && sql.contains("DELETE FROM assistant_messages")),
                eq(conversationId)
        )).thenThrow(new RuntimeException("purge skipped"));

        Map<String, Object> result = service.deleteConversation(
                conversationId.toString(),
                null,
                "device-1"
        );

        assertThat(result)
                .containsEntry("conversation_id", conversationId.toString())
                .containsEntry("status", "deleted")
                .containsEntry("deleted", true)
                .containsEntry("delete_mode", "soft");
    }

    @Test
    void deleteConversationReturnsNotFoundWhenOwnerDoesNotMatch() {
        UUID conversationId = UUID.randomUUID();
        AssistantConversationService service = new AssistantConversationService(
                jdbcTemplate,
                new ObjectMapper()
        );

        when(jdbcTemplate.update(
                argThat(sql -> sql != null && sql.contains("UPDATE assistant_conversations")),
                eq(conversationId),
                any()
        )).thenReturn(0);

        Map<String, Object> result = service.deleteConversation(
                conversationId.toString(),
                null,
                "device-1"
        );

        assertThat(result)
                .containsEntry("conversation_id", conversationId.toString())
                .containsEntry("status", "not_found")
                .containsEntry("deleted", false)
                .containsEntry("error_code", "ASSISTANT_CONVERSATION_NOT_FOUND");
    }
}
